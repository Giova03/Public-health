/**
 * PUBLIC HEALTH — client API typé.
 *
 * ARCHITECTURE SÉPARÉE (v2.0) : le frontend et le backend sont deux
 * applications indépendantes (voir ARCHITECTURE.md). La base des URL est
 * pilotée par NEXT_PUBLIC_API_BASE_URL :
 *  - VIDE (démo / développement) → routes simulées intégrées /api/v1/*
 *    (fidèles au contrat Spring Boot, même ProblemDetail, même 409) ;
 *  - RENSEIGNÉE (production) → API Spring Boot réelle (Render), CORS
 *    à autoriser côté backend.
 *
 * Enveloppe chaque appel REST /api/v1/* avec :
 *  - NetworkError quand le fetch échoue (hors ligne réel) ;
 *  - ApiError { status, body } quand le serveur répond en erreur,
 *    avec le ProblemDetail (contrat 409 doublons : body.candidates).
 *
 * Re-câblage = 1 variable d'environnement ; types et vues inchangés.
 */

const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(
  /\/+$/,
  "",
);

const BASE = `${API_BASE_URL || ""}/api/v1`;

import type {
  CreatePatientInput,
  DispenseLine,
  DuplicateCandidate,
  ExonerationNature,
  FraisAccesTicket,
  OperationAck,
  Patient,
  PaymentRecord,
  Prescription,
  PrescriptionItem,
  SyncOperation,
} from "@/lib/types";

/** Jeton de session (localStorage — évite la dépendance circulaire store). */
function jetonSession(): string | null {
  try {
    const raw = typeof window !== "undefined"
      ? window.localStorage.getItem("ph.session.v2")
      : null;
    if (!raw) return null;
    return (JSON.parse(raw) as { jeton?: string }).jeton ?? null;
  } catch {
    return null;
  }
}

export class NetworkError extends Error {
  constructor(message = "Réseau indisponible") {
    super(message);
    this.name = "NetworkError";
  }
}

export class ApiError extends Error {
  status: number;
  body: Record<string, unknown>;

  constructor(status: number, body: Record<string, unknown>) {
    super(
      typeof body.detail === "string"
        ? body.detail
        : typeof body.title === "string"
          ? body.title
          : `Erreur HTTP ${status}`,
    );
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  // V14 (I1) : chaque appel porte le jeton de session (Authorization:
  // Bearer) — l'API exige une authentification depuis le correctif I1.
  const jeton = jetonSession();
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      ...init,
      headers: {
        "content-type": "application/json",
        ...(jeton ? { authorization: `Bearer ${jeton}` } : {}),
        ...(init?.headers ?? {}),
      },
    });
  } catch (cause) {
    throw new NetworkError(cause instanceof Error ? cause.message : undefined);
  }

  if (!response.ok) {
    let body: Record<string, unknown> = {};
    try {
      body = (await response.json()) as Record<string, unknown>;
    } catch {
      body = {};
    }
    throw new ApiError(response.status, body);
  }
  return (await response.json()) as T;
}

/* ------------------------------- E1 patients -------------------------- */

export function searchPatients(query: {
  q?: string;
  family?: string;
  phone?: string;
}): Promise<{ patients: Patient[] }> {
  const params = new URLSearchParams();
  if (query.q) params.set("q", query.q);
  if (query.family) params.set("family", query.family);
  if (query.phone) params.set("phone", query.phone);
  return request(`/patients?${params.toString()}`);
}

export interface CreatePatientBody extends CreatePatientInput {
  forceCreate?: boolean;
  reason?: string;
}

export async function createPatient(
  body: CreatePatientBody,
): Promise<
  | { status: "created" | "replayed"; patient: Patient }
  | {
      status: "conflict";
      candidates: DuplicateCandidate[];
      /** Suggestion 4 / Q42 : 409 masqué pour un appelant sans patient:lire. */
      redacted: boolean;
      count?: number;
    }
> {
  try {
    const res = await request<{ patient: Patient; replayed?: boolean }>(
      "/patients",
      { method: "POST", body: JSON.stringify(body) },
    );
    return { status: res.replayed ? "replayed" : "created", patient: res.patient };
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      return {
        status: "conflict",
        candidates: (error.body.candidates as DuplicateCandidate[]) ?? [],
        redacted: error.body.candidatesRedacted === true,
        count: typeof error.body.candidatesCount === "number"
          ? error.body.candidatesCount
          : undefined,
      };
    }
    throw error;
  }
}

export async function getPatient(
  id: string,
): Promise<{ status: "ok"; patient: Patient } | { status: "gone"; masterId: string }> {
  try {
    const res = await request<{ patient: Patient }>(`/patients/${id}`);
    return { status: "ok", patient: res.patient };
  } catch (error) {
    if (error instanceof ApiError && error.status === 410) {
      return { status: "gone", masterId: String(error.body.masterId ?? "") };
    }
    throw error;
  }
}

/* ------------------------------- E2 sync ------------------------------ */

export function uplinkSync(
  deviceId: string,
  operations: SyncOperation[],
): Promise<{ deviceId: string; acks: OperationAck[] }> {
  return request("/sync", {
    method: "POST",
    body: JSON.stringify({ deviceId, operations }),
  });
}

export interface DeltaEntryDto {
  seq: number;
  at: string;
  kind: string;
  entity: Patient | Prescription | PaymentRecord;
}

export function pullDelta(
  cursor: number,
): Promise<{ cursor: number; entries: DeltaEntryDto[] }> {
  return request(`/sync/delta?cursor=${cursor}`);
}

/* ---------------------------- E3 prescriptions ------------------------ */

export function listPrescriptions(filter?: {
  patientId?: string;
  status?: string;
}): Promise<{ prescriptions: Prescription[] }> {
  const params = new URLSearchParams();
  if (filter?.patientId) params.set("patientId", filter.patientId);
  if (filter?.status) params.set("status", filter.status);
  return request(`/prescriptions?${params.toString()}`);
}

export function createPrescription(body: {
  patientId: string;
  prescriber: string;
  facility: string;
  diagnosis: string;
  items: PrescriptionItem[];
}): Promise<{ prescription: Prescription }> {
  return request("/prescriptions", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function dispensePrescription(
  prescriptionId: string,
  body:
    | {
        action: "DISPENSE";
        pharmacist: string;
        source?: "INTERNE" | "PRIVE";
        lines: DispenseLine[];
      }
    | {
        action: "COUNTER_ENTRY";
        pharmacist: string;
        counterEntryOf: string;
        lines: DispenseLine[];
      },
): Promise<{ prescription: Prescription }> {
  return request(`/prescriptions/${prescriptionId}`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/* ------------------------------- E4 payments -------------------------- */

export function listPayments(filter?: {
  patientId?: string;
  state?: string;
}): Promise<{ payments: PaymentRecord[] }> {
  const params = new URLSearchParams();
  if (filter?.patientId) params.set("patientId", filter.patientId);
  if (filter?.state) params.set("state", filter.state);
  return request(`/payments?${params.toString()}`);
}

export function initiatePayment(body: {
  clientRequestId: string;
  patientId: string;
  prescriptionId?: string;
  purpose: string;
  amountXof: number;
  channel: "MOBILE_MONEY" | "ESPECES" | "CARTE";
  facility: string;
}): Promise<{ payment: PaymentRecord; replayed?: boolean }> {
  return request("/payments", { method: "POST", body: JSON.stringify(body) });
}

export function progressPayment(
  paymentId: string,
  target: PaymentRecord["state"],
  event: "WEBHOOK" | "RECONCILIATION" = "WEBHOOK",
): Promise<{ payment: PaymentRecord }> {
  return request(`/payments/${paymentId}`, {
    method: "POST",
    body: JSON.stringify({ target, event }),
  });
}

export function runReconciliation(): Promise<{
  reconciled: number;
  orphansForReview: number;
  ranAt: string;
}> {
  return request("/payments/reconcile", { method: "POST" });
}

/* ---------------------- I5 frais d'accès (la caisse) ------------------- */

/** File d'attente de la caisse (en_attente du jour) OU historique patient. */
export function listFraisAcces(filter?: {
  patientId?: string;
  structureId?: string;
  statut?: string;
}): Promise<{ tickets: FraisAccesTicket[] }> {
  const params = new URLSearchParams();
  if (filter?.patientId) params.set("patientId", filter.patientId);
  if (filter?.structureId) params.set("structureId", filter.structureId);
  if (filter?.statut) params.set("statut", filter.statut);
  return request(`/frais-acces?${params.toString()}`);
}

/** Ouverture du ticket du jour — idempotente (rejeu = même ticket, 200). */
export function ouvrirTicket(body: {
  patientId: string;
  structureId?: string;
  montantXof?: number;
}): Promise<FraisAccesTicket> {
  return request<FraisAccesTicket>("/frais-acces", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** Encaissement espèces — forward-only (paye terminal, 409 sinon). */
export function encaisserTicket(
  id: string,
  montantXof?: number,
): Promise<FraisAccesTicket> {
  return request<FraisAccesTicket>(`/frais-acces/${id}/encaisser`, {
    method: "POST",
    body: JSON.stringify({ montantXof }),
  });
}

/** Exonération TRACÉE — nature + motif obligatoires, forward-only. */
export function exonererTicket(
  id: string,
  nature: ExonerationNature,
  motif: string,
): Promise<FraisAccesTicket> {
  return request<FraisAccesTicket>(`/frais-acces/${id}/exonerer`, {
    method: "POST",
    body: JSON.stringify({ nature, motif }),
  });
}

/* ------------------------------- E6 back-office ----------------------- */

export function getBackoffice(): Promise<{
  users: import("@/lib/types").StaffUser[];
  facilities: import("@/lib/types").HealthFacility[];
  stats: Record<string, number>;
  serverBoot: string;
}> {
  return request("/backoffice");
}

/* -------------------------------------------------------------------- */
/* V14 — Consultation, RDV, stock, référence, SNIS, audit, décès        */
/* -------------------------------------------------------------------- */

import type {
  AppointmentRecord,
  AuditEntryView,
  ConsultationConstantes,
  ConsultationRecord,
  ReferenceFiche,
  SnisStats,
  StockItem,
  StockMouvement,
} from "@/lib/types";

/** L'ACTE CLINIQUE COMPLET (I4) : motif + constantes + notes + diagnostic codé. */
export async function createConsultation(body: {
  patientId: string;
  motif: string;
  diagnosticCode: string;
  diagnosticLabel?: string;
  notes?: string;
  constantes?: ConsultationConstantes;
}): Promise<ConsultationRecord> {
  return request<ConsultationRecord>("/consultations", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function listConsultations(patientId: string): Promise<ConsultationRecord[]> {
  const reponse = await request<{ consultations: ConsultationRecord[] }>(
    `/consultations?patientId=${encodeURIComponent(patientId)}`,
  );
  return reponse.consultations;
}

export async function listAppointments(patientId?: string): Promise<AppointmentRecord[]> {
  const reponse = await request<{ appointments: AppointmentRecord[] }>(
    `/appointments${patientId ? `?patientId=${encodeURIComponent(patientId)}` : ""}`,
  );
  return reponse.appointments;
}

export async function createAppointment(body: {
  patientId?: string;
  type?: AppointmentRecord["type"];
  creneau: string;
  motif?: string;
}): Promise<AppointmentRecord> {
  return request<AppointmentRecord>("/appointments", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function transitionAppointment(
  id: string,
  action: "confirmer" | "honorer" | "absent" | "annuler",
  motif?: string,
): Promise<AppointmentRecord> {
  return request<AppointmentRecord>(`/appointments/${id}/${action}`, {
    method: "POST",
    body: JSON.stringify({ motif }),
  });
}

export async function getStock(structureId?: string): Promise<{
  items: StockItem[];
  mouvements: StockMouvement[];
  ruptures: number;
  sousSeuil: number;
}> {
  return request(`/stock${structureId ? `?structureId=${encodeURIComponent(structureId)}` : ""}`);
}

export async function createStockMouvement(body: {
  structureId?: string;
  medicationCode: string;
  medicationLabel?: string;
  type: "reception" | "ajustement";
  quantity: number;
  motif?: string;
}): Promise<StockItem> {
  return request<StockItem>("/stock", { method: "POST", body: JSON.stringify(body) });
}

export async function listReferences(): Promise<ReferenceFiche[]> {
  const reponse = await request<{ references: ReferenceFiche[] }>("/references");
  return reponse.references;
}

export async function createReference(body: {
  patientId: string;
  structureDestination: string;
  motif: string;
  urgence?: boolean;
}): Promise<ReferenceFiche> {
  return request<ReferenceFiche>("/references", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function transitionReference(
  id: string,
  action: "reception" | "hospitalisation" | "contre-reference",
  resume?: string,
): Promise<ReferenceFiche> {
  return request<ReferenceFiche>(`/references/${id}/${action}`, {
    method: "POST",
    body: JSON.stringify({ resume }),
  });
}

export async function getSnis(structureId?: string): Promise<SnisStats> {
  return request(`/statistics/snis${structureId ? `?structureId=${encodeURIComponent(structureId)}` : ""}`);
}

export async function listAuditEntries(limit = 100): Promise<AuditEntryView[]> {
  const reponse = await request<{ entries: AuditEntryView[] }>(`/audit/entries?limit=${limit}`);
  return reponse.entries;
}

/** Déclaration de décès (I15) — le dossier est scellé. */
export async function declareDeath(patientId: string, cause: string): Promise<void> {
  await request(`/patients/${patientId}/deces`, {
    method: "POST",
    body: JSON.stringify({ cause }),
  });
}
