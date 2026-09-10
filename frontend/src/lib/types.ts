/**
 * PUBLIC HEALTH — Contrats de types partagés (front + simulation API).
 *
 * Ces types reflètent les contrats REST livrés côté backend Spring Boot
 * (épiques E1 à E6 du backlog P0) :
 *  - E1 Identité & MPI : recherche miroir, 409 doublons = contrat UX,
 *    ph_reference PH-AAAA-NNNNNN, 410 dossier fusionné (masterId).
 *  - E2 Offline & sync : opérations outbox UUID v7 (opId), lot idempotent,
 *    delta par curseur.
 *  - E3 Ordonnances : append-only, dispensation partielle cumulée,
 *    contre-entrées.
 *  - E4 Paiements : 8 états forward-only (5 nominaux + 3 branches),
 *    idempotence par clientRequestId, réconciliation nocturne fait-foi.
 *  - E6 Back-office : utilisateurs, structures, rôles.
 *
 * L'application de démonstration simule ce backend via des routes
 * /api/v1/* ; le jour du re-câblage sur l'API Java réelle, seul
 * `lib/api-client.ts` change : ces types restent.
 */

/* ------------------------------------------------------------------ */
/* Vue application (navigation mono-page : l'utilisateur ne voit que /) */
/* ------------------------------------------------------------------ */

export type ViewId =
  | "dashboard"
  | "patients"
  | "consultation"
  | "prescriptions"
  | "payments"
  | "sync"
  | "backoffice";

/* ------------------------------------------------------------------ */
/* E1 — Identité & MPI                                                */
/* ------------------------------------------------------------------ */

/** Verdict de rapprochement identitaire (mêmes seuils que le backend). */
export type MatchVerdict = "BLOCKING" | "REVIEW";

export type PatientGender = "M" | "F";

/** Identifiant national : NUNP (numéro unique national du patient) ou CNIB. */
export interface PatientIdentifier {
  type: "NUNP" | "CNIB";
  value: string;
}

export interface PatientName {
  family: string;
  given: string;
}

export interface Patient {
  id: string;
  /** Identifiant interne lisible : PH-AAAA-NNNNNN (séquence annuelle). */
  phReference: string;
  /** Idempotence offline (colonne unique côté backend, migration V6). */
  clientRequestId?: string;
  /** Motif tracé en audit en cas de création forcée après 409. */
  forcedReason?: string;
  name: PatientName;
  gender: PatientGender;
  birthDate: string; // ISO YYYY-MM-DD
  phone?: string;
  identifiers: PatientIdentifier[];
  /** Code/ville de la structure de recensement (CSPS, CMA, CHR, CHU). */
  facility: string;
  village?: string;
  /** Faux dossier fusionné : false + masterId renvoyé (HTTP 410). */
  active: boolean;
  masterId?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  /** Marqueur local : entité créée/modifiée hors ligne, pas encore purgée. */
  pendingSync?: boolean;
}

/** Candidat doublon renvoyé dans le corps du 409 (contrat UX E1/ADR-003). */
export interface DuplicateCandidate {
  patient: Patient;
  score: number; // 0..1
  verdict: MatchVerdict;
  /** Preuves concrètes du rapprochement (ex. « NUNP identique »). */
  reasons: string[];
}

/** Corps d'une requête de création (idempotente par clientRequestId). */
export interface CreatePatientInput {
  clientRequestId: string;
  name: PatientName;
  gender: PatientGender;
  birthDate: string;
  phone?: string;
  identifiers?: PatientIdentifier[];
  facility: string;
  village?: string;
}

/** Réponse 409 ProblemDetail — propriété `candidates` embarquée. */
export interface ConflictPatientBody {
  title: string;
  detail: string;
  status: 409;
  candidates: DuplicateCandidate[];
}

/* ------------------------------------------------------------------ */
/* E2 — Offline & synchronisation                                      */
/* ------------------------------------------------------------------ */

export type OperationKind =
  | "patient.create"
  | "patient.update"
  | "prescription.create"
  | "prescription.dispense"
  | "prescription.counterEntry"
  | "payment.initiate";

export type OperationStatus = "queued" | "sent" | "acked" | "failed";

/**
 * Opération hors ligne : identifiant opId UUID v7 généré côté client
 * (ordre chronologique même hors ligne), drainée par lot idempotent.
 */
export interface SyncOperation {
  opId: string;
  kind: OperationKind;
  entityId: string;
  payload: unknown;
  createdAt: string;
  attempts: number;
  status: OperationStatus;
  lastError?: string;
}

/** Réponse du drain d'une opération (POST /api/v1/sync). */
export interface OperationAck {
  opId: string;
  /** applied = appliquée, duplicate = rejeu idempotent déjà vu. */
  result: "applied" | "duplicate" | "rejected";
  entity?: Patient | Prescription | PaymentRecord;
  error?: string;
}

export interface SyncLogEntry {
  id: string;
  at: string;
  direction: "uplink" | "downlink";
  summary: string;
  ok: boolean;
}

/* ------------------------------------------------------------------ */
/* E3 — Ordonnances & dispensation                                    */
/* ------------------------------------------------------------------ */

export type PrescriptionStatus = "ACTIVE" | "COMPLETED" | "CANCELLED";

export interface PrescriptionItem {
  /** Médicament de la liste nationale (DCI). */
  drug: string;
  dosage: string; // ex. "500 mg"
  frequency: string; // ex. "3×/jour"
  durationDays: number;
  quantity: number;
}

/** Dispensation partielle : cumul contrôlé, reste = quantité − dispensé. */
export interface DispenseLine {
  drug: string;
  quantity: number;
}

export interface DispenseEvent {
  id: string;
  at: string;
  pharmacist: string;
  lines: DispenseLine[];
  source: "INTERNE" | "PRIVE";
  /** Contre-entrée : annulation tracée d'une dispensation erronée. */
  counterEntryOf?: string;
}

export interface Prescription {
  id: string;
  patientId: string;
  patientName?: PatientName;
  prescriber: string;
  facility: string;
  date: string;
  diagnosis: string;
  items: PrescriptionItem[];
  dispenses: DispenseEvent[];
  status: PrescriptionStatus;
  createdAt: string;
  pendingSync?: boolean;
}

/* ------------------------------------------------------------------ */
/* E4 — Paiements                                                     */
/* ------------------------------------------------------------------ */

export type PaymentState =
  | "INITIATED"
  | "PENDING"
  | "AUTHORIZED"
  | "SUCCEEDED"
  | "RECONCILED"
  | "FAILED"
  | "CANCELLED"
  | "REFUNDED";

/** Transitions autorisées (forward-only, machine à 8 états). */
export const PAYMENT_TRANSITIONS: Record<PaymentState, PaymentState[]> = {
  INITIATED: ["PENDING", "FAILED", "CANCELLED"],
  PENDING: ["AUTHORIZED", "FAILED", "CANCELLED"],
  AUTHORIZED: ["SUCCEEDED", "FAILED"],
  SUCCEEDED: ["RECONCILED", "REFUNDED"],
  RECONCILED: [],
  FAILED: [],
  CANCELLED: [],
  REFUNDED: [],
};

export type PaymentChannel = "MOBILE_MONEY" | "ESPECES" | "CARTE";

export interface PaymentRecord {
  id: string;
  /** Idempotence offline : généré côté client (UUID v7). */
  clientRequestId: string;
  patientId: string;
  patientName?: PatientName;
  prescriptionId?: string;
  purpose: string;
  amountXof: number;
  channel: PaymentChannel;
  state: PaymentState;
  facility: string;
  /** Heure de la réconciliation nocturne (fait-foi) le cas échéant. */
  reconciledAt?: string;
  createdAt: string;
  updatedAt: string;
  pendingSync?: boolean;
}

/* ------------------------------------------------------------------ */
/* E6 — Back-office                                                   */
/* ------------------------------------------------------------------ */

export type StaffRole =
  | "AGENT_SAISIE"
  | "INFIRMIER"
  | "MEDECIN"
  | "PHARMACIEN"
  | "CAISSIER"
  | "SUPERVISEUR"
  | "ADMIN";

export interface StaffUser {
  id: string;
  fullName: string;
  role: StaffRole;
  facility: string;
  mfaEnabled: boolean;
  active: boolean;
  lastSeenAt: string;
}

export type FacilityKind = "CSPS" | "CMA" | "CHR" | "CHU" | "DRS";

export interface HealthFacility {
  id: string;
  name: string;
  kind: FacilityKind;
  region: string;
  staffCount: number;
  online: boolean;
}

/* ------------------------------------------------------------------ */
/* Utilitaires                                                        */
/* ------------------------------------------------------------------ */

/** Utilitaires (implémentations : lib/uuid.ts). */
/** Format monnaie XOF : 12 500 F CFA (chiffres tabulaires côté rendu). */
export function formatXof(amount: number): string {
  return `${amount.toLocaleString("fr-FR")} F CFA`;
}

/** Date courte FR : 10/09/2026. */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("fr-FR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });
}

/** Heure courte FR : 14:05. */
export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("fr-FR", {
    hour: "2-digit",
    minute: "2-digit",
  });
}
