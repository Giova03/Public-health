"use client";

/**
 * PUBLIC HEALTH — store applicatif central (zustand).
 *
 * Le miroir local (IndexedDB → state) est la source d'affichage ;
 * les mutations passent soit directement par l'API (en ligne), soit par
 * l'outbox avec entité optimiste marquée `pendingSync` (hors ligne),
 * drainée ensuite de manière idempotente par opId (protocole E2).
 */

import { create } from "zustand";
import type {
  AppointmentRecord,
  AuditEntryView,
  ConsultationConstantes,
  ConsultationRecord,
  ReferenceFiche,
  SnisStats,
  StockItem,
  StockMouvement,
  CreatePatientInput,
  DispenseLine,
  DuplicateCandidate,
  HealthFacility,
  Patient,
  PaymentRecord,
  Prescription,
  PrescriptionItem,
  StaffUser,
  SyncLogEntry,
  SyncOperation,
  ViewId,
} from "@/lib/types";
import { uuidV7 } from "@/lib/uuid";
import * as db from "@/lib/offline/db";
import {
  createAppointment as apiCreateAppointment,
  createConsultation as apiCreateConsultation,
  createReference as apiCreateReference,
  createStockMouvement as apiCreateStockMouvement,
  declareDeath as apiDeclareDeath,
  getSnis as apiGetSnis,
  getStock as apiGetStock,
  listAppointments as apiListAppointments,
  listAuditEntries as apiListAudit,
  listConsultations as apiListConsultations,
  listReferences as apiListReferences,
  transitionAppointment as apiTransitionAppointment,
  transitionReference as apiTransitionReference,
} from "@/lib/api-client";
import {
  ApiError,
  NetworkError,
  createPatient as apiCreatePatient,
  createPrescription as apiCreatePrescription,
  dispensePrescription as apiDispense,
  getBackoffice,
  initiatePayment as apiInitiatePayment,
  progressPayment as apiProgressPayment,
  pullDelta,
  runReconciliation as apiRunReconciliation,
  uplinkSync,
} from "@/lib/api-client";

/* ----------------------------- Types locaux --------------------------- */

export type CreatePatientResult =
  | { status: "created" | "replayed"; patient: Patient }
  | { status: "queued"; patient: Patient }
  | {
      status: "conflict";
      candidates: DuplicateCandidate[];
      /** Suggestion 4 / Q42 : candidats masqués (appelant sans patient:lire). */
      redacted: boolean;
      count?: number;
    }
  | { status: "error"; message: string };

export type MutationResult =
  | { status: "ok" }
  | { status: "queued" }
  | { status: "error"; message: string };

interface AppState {
  /* Navigation (mono-page : l'utilisateur ne voit que « / ») */
  view: ViewId;
  selectedPatientId: string | null;
  selectedPrescriptionId: string | null;
  goTo: (view: ViewId) => void;
  selectPatient: (id: string | null) => void;
  selectPrescription: (id: string | null) => void;

  /* Réseau simulé + hydratation */
  simulatedOnline: boolean;
  hydrated: boolean;
  syncing: boolean;
  toggleOnline: () => void;
  isOnline: () => boolean;

  /* Miroir local */
  patients: Patient[];
  prescriptions: Prescription[];
  payments: PaymentRecord[];
  users: StaffUser[];
  facilities: HealthFacility[];
  /* V14 — correction audit de fidélité */
  consultations: ConsultationRecord[];
  appointments: AppointmentRecord[];
  stockItems: StockItem[];
  stockMouvements: StockMouvement[];
  references: ReferenceFiche[];
  auditEntries: AuditEntryView[];
  snis: SnisStats | null;

  /* Outbox & protocole E2 */
  outbox: SyncOperation[];
  syncLog: SyncLogEntry[];
  cursor: number;
  lastSyncAt: string | null;
  deviceId: string | null;

  /* Cycle de vie */
  hydrate: () => Promise<void>;
  syncNow: (opts?: { auto?: boolean }) => Promise<void>;
  refreshBackoffice: () => Promise<void>;

  /* Actions métier */
  createPatient: (
    input: CreatePatientInput,
    opts?: { forceCreate?: boolean; reason?: string },
  ) => Promise<CreatePatientResult>;
  createPrescription: (payload: {
    patientId: string;
    prescriber: string;
    facility: string;
    diagnosis: string;
    items: PrescriptionItem[];
  }) => Promise<MutationResult>;
  dispense: (payload: {
    prescriptionId: string;
    pharmacist: string;
    source: "INTERNE" | "PRIVE";
    lines: DispenseLine[];
  }) => Promise<MutationResult>;
  counterEntry: (payload: {
    prescriptionId: string;
    dispenseId: string;
    pharmacist: string;
  }) => Promise<MutationResult>;
  initiatePayment: (payload: {
    patientId: string;
    prescriptionId?: string;
    purpose: string;
    amountXof: number;
    channel: "MOBILE_MONEY" | "ESPECES" | "CARTE";
    facility: string;
  }) => Promise<MutationResult>;
  progressPayment: (
    paymentId: string,
    target: PaymentRecord["state"],
  ) => Promise<MutationResult>;
  runReconciliation: () => Promise<MutationResult>;

  /* V14 — actions fidélité */
  createConsultation: (payload: {
    patientId: string;
    motif: string;
    diagnosticCode: string;
    diagnosticLabel?: string;
    notes?: string;
    constantes?: ConsultationConstantes;
  }) => Promise<MutationResult>;
  loadConsultations: (patientId: string) => Promise<void>;
  createAppointment: (payload: {
    patientId?: string;
    type?: AppointmentRecord["type"];
    creneau: string;
    motif?: string;
  }) => Promise<MutationResult>;
  transitionAppointment: (
    id: string,
    action: "confirmer" | "honorer" | "absent" | "annuler",
    motif?: string,
  ) => Promise<MutationResult>;
  loadAppointments: (patientId?: string) => Promise<void>;
  loadStock: () => Promise<void>;
  createStockMouvement: (payload: {
    medicationCode: string;
    medicationLabel?: string;
    type: "reception" | "ajustement";
    quantity: number;
    motif?: string;
  }) => Promise<MutationResult>;
  loadReferences: () => Promise<void>;
  createReference: (payload: {
    patientId: string;
    structureDestination: string;
    motif: string;
    urgence?: boolean;
  }) => Promise<MutationResult>;
  transitionReference: (
    id: string,
    action: "reception" | "hospitalisation" | "contre-reference",
    resume?: string,
  ) => Promise<MutationResult>;
  loadSnis: () => Promise<void>;
  loadAudit: () => Promise<void>;
  declareDeath: (patientId: string, cause: string) => Promise<MutationResult>;
  cancelPrescription: (
    id: string,
    kind: "CANCELLED" | "ENTERED_IN_ERROR",
    motif: string,
  ) => Promise<MutationResult>;
}

/* --------------------------- Helpers internes ------------------------- */

const DEVICE_LABEL = "Poste infirmerie · CSPS Ouaga 12";

function kindOfEntity(entity: { phReference?: string; dispenses?: unknown; state?: string }): db.MirrorKind {
  if (typeof entity.state === "string") return "payment";
  if (Array.isArray(entity.dispenses)) return "prescription";
  return "patient";
}

/** Total dispensé net (contre-entrées déduites) par médicament. */
export function netDispensed(prescription: Prescription): Map<string, number> {
  const net = new Map<string, number>();
  for (const d of prescription.dispenses) {
    const sign = d.counterEntryOf ? -1 : 1;
    for (const line of d.lines) {
      net.set(line.drug, (net.get(line.drug) ?? 0) + sign * line.quantity);
    }
  }
  return net;
}

function appendLog(
  current: SyncLogEntry[],
  entries: SyncLogEntry[],
): SyncLogEntry[] {
  return [...current, ...entries].slice(-50);
}

/* ------------------------------- Store -------------------------------- */

export const useAppStore = create<AppState>((set, get) => ({
  view: "dashboard",
  selectedPatientId: null,
  selectedPrescriptionId: null,
  goTo: (view) => set({ view }),
  selectPatient: (id) => set({ selectedPatientId: id }),
  selectPrescription: (id) => set({ selectedPrescriptionId: id }),

  simulatedOnline: true,
  hydrated: false,
  syncing: false,
  toggleOnline: () => {
    const next = !get().simulatedOnline;
    set({ simulatedOnline: next });
    if (next) {
      void get()
        .syncNow({ auto: true })
        .then(() => get().refreshBackoffice());
    }
  },
  isOnline: () =>
    get().simulatedOnline &&
    typeof navigator !== "undefined" &&
    navigator.onLine,

  patients: [],
  prescriptions: [],
  payments: [],
  consultations: [],
  appointments: [],
  stockItems: [],
  stockMouvements: [],
  references: [],
  auditEntries: [],
  snis: null,
  users: [],
  facilities: [],

  outbox: [],
  syncLog: [],
  cursor: 0,
  lastSyncAt: null,
  deviceId: null,

  hydrate: async () => {
    if (get().hydrated) return;
    try {
      const [mirrorRows, outboxOps, cursor, lastSyncAt, deviceId] =
        await Promise.all([
          db.getAllMirror(),
          db.getAllOutbox(),
          db.getMeta<number>("cursor"),
          db.getMeta<string>("lastSyncAt"),
          db.getMeta<string>("deviceId"),
        ]);

      const patients: Patient[] = [];
      const prescriptions: Prescription[] = [];
      const payments: PaymentRecord[] = [];
      for (const row of mirrorRows) {
        if (row.kind === "patient") patients.push(row.data as Patient);
        else if (row.kind === "prescription")
          prescriptions.push(row.data as Prescription);
        else if (row.kind === "payment") payments.push(row.data as PaymentRecord);
      }

      const resolvedDeviceId = deviceId ?? uuidV7();
      if (!deviceId) await db.setMeta("deviceId", resolvedDeviceId);

      set({
        patients,
        prescriptions,
        payments,
        outbox: outboxOps.sort((a, b) =>
          a.createdAt.localeCompare(b.createdAt),
        ),
        cursor: cursor ?? 0,
        lastSyncAt: lastSyncAt ?? null,
        deviceId: resolvedDeviceId,
        hydrated: true,
      });
    } catch {
      // IndexedDB indisponible (mode privé navigateur) : dégradation
      // honnête — session en mémoire, sans persistance.
      set({ deviceId: uuidV7(), hydrated: true });
    }

    if (get().isOnline()) {
      await get().syncNow({ auto: true });
      void get().refreshBackoffice();
    }
  },

  syncNow: async (opts) => {
    if (get().syncing || !get().isOnline()) return;
    set({ syncing: true });
    const entries: SyncLogEntry[] = [];
    const state = get();

    /* 1. Drain de l'outbox (idempotent par opId). */
    const pending = state.outbox.filter(
      (op) => op.status === "queued" || op.status === "sent",
    );
    if (pending.length > 0) {
      try {
        const { acks } = await uplinkSync(state.deviceId ?? "unknown", pending);
        const ackByOpId = new Map(acks.map((a) => [a.opId, a]));
        const drainedIds = new Set(pending.map((op) => op.opId));

        const nextOutbox: SyncOperation[] = [];
        const refreshed: {
          patients: Patient[];
          prescriptions: Prescription[];
          payments: PaymentRecord[];
        } = { patients: [], prescriptions: [], payments: [] };

        for (const op of get().outbox) {
          if (!drainedIds.has(op.opId)) {
            nextOutbox.push(op); // ajoutée pendant le drain, hors périmètre
            continue;
          }
          const ack = ackByOpId.get(op.opId);
          if (!ack) {
            // Accusé perdu : l'opération reste en file — le rejeu du même
            // opId sera idempotent côté serveur (contrat E2).
            nextOutbox.push({ ...op, attempts: op.attempts + 1 });
            continue;
          }
          if (ack.result === "rejected") {
            const failed: SyncOperation = {
              ...op,
              status: "failed",
              attempts: op.attempts + 1,
              lastError: ack.error ?? "Rejet serveur",
            };
            nextOutbox.push(failed);
            await db.putOutbox(failed);
            entries.push({
              id: uuidV7(),
              at: new Date().toISOString(),
              direction: "uplink",
              summary: `Opération ${op.kind} rejetée : ${failed.lastError}`,
              ok: false,
            });
            continue;
          }
          // applied | duplicate : purge de l'outbox, miroir rafraîchi.
          await db.deleteOutbox(op.opId);
          if (ack.entity) {
            await db.putMirror(kindOfEntity(ack.entity), ack.entity);
            const kind = kindOfEntity(ack.entity);
            if (kind === "patient") refreshed.patients.push(ack.entity as Patient);
            else if (kind === "prescription") refreshed.prescriptions.push(ack.entity as Prescription);
            else refreshed.payments.push(ack.entity as PaymentRecord);
          }
          entries.push({
            id: uuidV7(),
            at: new Date().toISOString(),
            direction: "uplink",
            summary: `${op.kind} → ${ack.result === "duplicate" ? "rejeu idempotent (déjà vu)" : "appliquée"}`,
            ok: true,
          });
        }

        set((s) => ({
          outbox: nextOutbox,
          patients: mergeById(s.patients, refreshed.patients),
          prescriptions: mergeById(s.prescriptions, refreshed.prescriptions),
          payments: mergeById(s.payments, refreshed.payments),
        }));
      } catch (error) {
        const message =
          error instanceof NetworkError
            ? "Drain interrompu : réseau indisponible. Les opérations restent en file."
            : `Drain refusé : ${error instanceof Error ? error.message : "erreur inconnue"}`;
        entries.push({
          id: uuidV7(),
          at: new Date().toISOString(),
          direction: "uplink",
          summary: message,
          ok: false,
        });
      }
    }

    /* 2. Pull delta (curseur avancé, entités pendingSync protégées). */
    try {
      const { cursor: nextCursor, entries: deltaEntries } = await pullDelta(
        get().cursor,
      );
      const pendingIds = new Set(
        get()
          .patients.filter((p) => p.pendingSync)
          .map((p) => p.id),
      );
      get()
        .prescriptions.filter((p) => p.pendingSync)
        .forEach((p) => pendingIds.add(p.id));
      get()
        .payments.filter((p) => p.pendingSync)
        .forEach((p) => pendingIds.add(p.id));

      const patients: Patient[] = [];
      const prescriptions: Prescription[] = [];
      const payments: PaymentRecord[] = [];
      for (const entry of deltaEntries) {
        if (pendingIds.has(entry.entity.id)) continue;
        const kind = kindOfEntity(entry.entity);
        if (kind === "patient") patients.push(entry.entity as Patient);
        else if (kind === "prescription")
          prescriptions.push(entry.entity as Prescription);
        else payments.push(entry.entity as PaymentRecord);
        await db.putMirror(kind, entry.entity);
      }

      const now = new Date().toISOString();
      await db.setMeta("cursor", nextCursor);
      await db.setMeta("lastSyncAt", now);
      entries.push({
        id: uuidV7(),
        at: now,
        direction: "downlink",
        summary:
          deltaEntries.length > 0
            ? `${deltaEntries.length} changement(s) serveur récupéré(s)`
            : "Aucun changement côté serveur",
        ok: true,
      });

      set((s) => ({
        patients: mergeById(s.patients, patients),
        prescriptions: mergeById(s.prescriptions, prescriptions),
        payments: mergeById(s.payments, payments),
        cursor: nextCursor,
        lastSyncAt: now,
      }));
    } catch (error) {
      entries.push({
        id: uuidV7(),
        at: new Date().toISOString(),
        direction: "downlink",
        summary:
          error instanceof NetworkError
            ? "Tirage delta impossible : réseau indisponible."
            : `Tirage delta refusé : ${error instanceof Error ? error.message : "erreur inconnue"}`,
        ok: false,
      });
    }

    set((s) => ({
      syncing: false,
      syncLog: appendLog(s.syncLog, entries),
    }));
  },

  refreshBackoffice: async () => {
    if (!get().isOnline()) return;
    try {
      const data = await getBackoffice();
      set({ users: data.users, facilities: data.facilities });
    } catch {
      /* silencieux : la vue back-office affichera l'état du miroir */
    }
  },

  /* ------------------------------ E1 ------------------------------ */

  createPatient: async (input, opts) => {
    const now = new Date().toISOString();

    if (!get().isOnline()) {
      // Hors ligne : entité optimiste + opération outbox. La référence MPI
      // (PH-AAAA-NNNNNN) sera attribuée par le serveur au drain — on
      // l'affiche honnêtement comme « à la synchronisation ».
      const patient: Patient = {
        id: uuidV7(),
        phReference: "",
        clientRequestId: input.clientRequestId,
        name: input.name,
        gender: input.gender,
        birthDate: input.birthDate,
        phone: input.phone,
        identifiers: input.identifiers ?? [],
        facility: input.facility,
        village: input.village,
        active: true,
        version: 1,
        createdAt: now,
        updatedAt: now,
        pendingSync: true,
      };
      const op: SyncOperation = {
        opId: uuidV7(),
        kind: "patient.create",
        entityId: patient.id,
        payload: input,
        createdAt: now,
        attempts: 0,
        status: "queued",
      };
      await db.putMirror("patient", patient);
      await db.putOutbox(op);
      set((s) => ({
        patients: [...s.patients, patient],
        outbox: [...s.outbox, op],
      }));
      return { status: "queued", patient };
    }

    try {
      const result = await apiCreatePatient({
        ...input,
        ...(opts?.forceCreate ? { forceCreate: true, reason: opts.reason } : {}),
      });
      if (result.status === "conflict") {
        return {
          status: "conflict",
          candidates: result.candidates,
          redacted: result.redacted,
          count: result.count,
        };
      }
      await db.putMirror("patient", result.patient);
      set((s) => ({
        patients: mergeById(s.patients, [result.patient]),
      }));
      return { status: result.status, patient: result.patient };
    } catch (error) {
      return {
        status: "error",
        message:
          error instanceof NetworkError
            ? "Réseau indisponible au moment de la création."
            : error instanceof Error
              ? error.message
              : "Erreur inconnue",
      };
    }
  },

  /* ------------------------------ E3 ------------------------------ */

  createPrescription: async (payload) => {
    const now = new Date().toISOString();
    const patient = get().patients.find((p) => p.id === payload.patientId);

    if (!get().isOnline()) {
      const prescription: Prescription = {
        id: uuidV7(),
        patientId: payload.patientId,
        ...(patient ? { patientName: patient.name } : {}),
        prescriber: payload.prescriber,
        facility: payload.facility,
        date: now,
        diagnosis: payload.diagnosis,
        items: payload.items,
        dispenses: [],
        status: "ACTIVE",
        createdAt: now,
        pendingSync: true,
      };
      const op: SyncOperation = {
        opId: uuidV7(),
        kind: "prescription.create",
        entityId: prescription.id,
        payload,
        createdAt: now,
        attempts: 0,
        status: "queued",
      };
      await db.putMirror("prescription", prescription);
      await db.putOutbox(op);
      set((s) => ({
        prescriptions: [prescription, ...s.prescriptions],
        outbox: [...s.outbox, op],
      }));
      return { status: "queued" };
    }

    try {
      const { prescription } = await apiCreatePrescription(payload);
      await db.putMirror("prescription", prescription);
      set((s) => ({
        prescriptions: mergeById(s.prescriptions, [prescription]),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  dispense: async (payload) => {
    const now = new Date().toISOString();
    const prescription = get().prescriptions.find(
      (p) => p.id === payload.prescriptionId,
    );
    if (!prescription) {
      return { status: "error", message: "Ordonnance introuvable dans le miroir." };
    }

    // Garde locale (même règle que le serveur) : cumul net ≤ prescrit.
    const net = netDispensed(prescription);
    for (const line of payload.lines) {
      const prescribed = prescription.items.find((i) => i.drug === line.drug);
      if (!prescribed) {
        return {
          status: "error",
          message: `${line.drug} ne figure pas sur l'ordonnance.`,
        };
      }
      if ((net.get(line.drug) ?? 0) + line.quantity > prescribed.quantity) {
        return {
          status: "error",
          message: `${line.drug} : reste ${prescribed.quantity - (net.get(line.drug) ?? 0)}, demandé ${line.quantity}.`,
        };
      }
    }

    if (!get().isOnline()) {
      const event = {
        id: uuidV7(),
        at: now,
        pharmacist: payload.pharmacist,
        source: payload.source,
        lines: payload.lines,
      };
      const updated = recomputeStatus({
        ...prescription,
        dispenses: [...prescription.dispenses, event],
        pendingSync: true,
      });
      const op: SyncOperation = {
        opId: uuidV7(),
        kind: "prescription.dispense",
        entityId: event.id,
        payload,
        createdAt: now,
        attempts: 0,
        status: "queued",
      };
      await db.putMirror("prescription", updated);
      await db.putOutbox(op);
      set((s) => ({
        prescriptions: mergeById(s.prescriptions, [updated]),
        outbox: [...s.outbox, op],
      }));
      return { status: "queued" };
    }

    try {
      const { prescription: updated } = await apiDispense(
        payload.prescriptionId,
        {
          action: "DISPENSE",
          pharmacist: payload.pharmacist,
          source: payload.source,
          lines: payload.lines,
        },
      );
      await db.putMirror("prescription", updated);
      set((s) => ({
        prescriptions: mergeById(s.prescriptions, [updated]),
      }));
      return { status: "ok" };
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        return { status: "error", message: error.message };
      }
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  counterEntry: async (payload) => {
    const now = new Date().toISOString();
    const prescription = get().prescriptions.find(
      (p) => p.id === payload.prescriptionId,
    );
    if (!prescription) {
      return { status: "error", message: "Ordonnance introuvable dans le miroir." };
    }
    const target = prescription.dispenses.find((d) => d.id === payload.dispenseId);
    if (!target) {
      return { status: "error", message: "Dispensation d'origine introuvable." };
    }
    if (target.counterEntryOf) {
      return {
        status: "error",
        message: "Une contre-entrée ne se contre-entre pas.",
      };
    }

    if (!get().isOnline()) {
      const event = {
        id: uuidV7(),
        at: now,
        pharmacist: payload.pharmacist,
        source: target.source,
        lines: target.lines,
        counterEntryOf: target.id,
      };
      const updated = recomputeStatus({
        ...prescription,
        dispenses: [...prescription.dispenses, event],
        pendingSync: true,
      });
      const op: SyncOperation = {
        opId: uuidV7(),
        kind: "prescription.counterEntry",
        entityId: event.id,
        payload: {
          prescriptionId: payload.prescriptionId,
          pharmacist: payload.pharmacist,
          source: target.source,
          lines: target.lines,
          counterEntryOf: target.id,
        },
        createdAt: now,
        attempts: 0,
        status: "queued",
      };
      await db.putMirror("prescription", updated);
      await db.putOutbox(op);
      set((s) => ({
        prescriptions: mergeById(s.prescriptions, [updated]),
        outbox: [...s.outbox, op],
      }));
      return { status: "queued" };
    }

    try {
      const { prescription: updated } = await apiDispense(
        payload.prescriptionId,
        {
          action: "COUNTER_ENTRY",
          pharmacist: payload.pharmacist,
          counterEntryOf: payload.dispenseId,
          lines: target.lines,
        },
      );
      await db.putMirror("prescription", updated);
      set((s) => ({
        prescriptions: mergeById(s.prescriptions, [updated]),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  /* ------------------------------ E4 ------------------------------ */

  initiatePayment: async (payload) => {
    const now = new Date().toISOString();
    const patient = get().patients.find((p) => p.id === payload.patientId);
    const clientRequestId = uuidV7();

    if (!get().isOnline()) {
      const payment: PaymentRecord = {
        id: uuidV7(),
        clientRequestId,
        patientId: payload.patientId,
        ...(patient ? { patientName: patient.name } : {}),
        ...(payload.prescriptionId
          ? { prescriptionId: payload.prescriptionId }
          : {}),
        purpose: payload.purpose,
        amountXof: payload.amountXof,
        channel: payload.channel,
        state: "INITIATED",
        facility: payload.facility,
        createdAt: now,
        updatedAt: now,
        pendingSync: true,
      };
      const op: SyncOperation = {
        opId: uuidV7(),
        kind: "payment.initiate",
        entityId: payment.id,
        payload: { ...payload, clientRequestId },
        createdAt: now,
        attempts: 0,
        status: "queued",
      };
      await db.putMirror("payment", payment);
      await db.putOutbox(op);
      set((s) => ({
        payments: [payment, ...s.payments],
        outbox: [...s.outbox, op],
      }));
      return { status: "queued" };
    }

    try {
      const { payment } = await apiInitiatePayment({
        ...payload,
        clientRequestId,
      });
      await db.putMirror("payment", payment);
      set((s) => ({
        payments: mergeById(s.payments, [payment]),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  progressPayment: async (paymentId, target) => {
    if (!get().isOnline()) {
      return {
        status: "error",
        message:
          "Cette action simule un webhook opérateur : elle exige le réseau. Le paiement reprendra sa progression à la reconnexion.",
      };
    }
    try {
      const { payment } = await apiProgressPayment(paymentId, target);
      await db.putMirror("payment", payment);
      set((s) => ({
        payments: mergeById(s.payments, [payment]),
      }));
      return { status: "ok" };
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        return {
          status: "error",
          message: `Transition interdite (forward-only) : ${error.message}`,
        };
      }
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  runReconciliation: async () => {
    if (!get().isOnline()) {
      return {
        status: "error",
        message: "La réconciliation nocturne est un job serveur : réseau requis.",
      };
    }
    try {
      const summary = await apiRunReconciliation();
      await get().syncNow();
      set((s) => ({
        syncLog: appendLog(s.syncLog, [
          {
            id: uuidV7(),
            at: summary.ranAt,
            direction: "downlink",
            summary: `Réconciliation nocturne : ${summary.reconciled} paiement(s) réconcilié(s), ${summary.orphansForReview} orphelin(s) à revoir.`,
            ok: true,
          },
        ]),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  /* ------------------------------------------------------------------ */
  /* V14 — actions de fidélité (audit I1-I16)                            */
  /* ------------------------------------------------------------------ */

  createConsultation: async (payload) => {
    if (!get().isOnline()) {
      return { status: "error", message: "La consultation se rédige en ligne (synchro des actes : P0.6)." };
    }
    try {
      const consultation = await apiCreateConsultation(payload);
      set((s) => ({ consultations: [consultation, ...s.consultations] }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  loadConsultations: async (patientId) => {
    if (!get().isOnline()) return;
    try {
      const consultations = await apiListConsultations(patientId);
      set((s) => ({
        consultations: [
          ...consultations,
          ...s.consultations.filter((c) => c.patientId !== patientId),
        ],
      }));
    } catch {
      /* miroir inchangé */
    }
  },

  createAppointment: async (payload) => {
    if (!get().isOnline()) {
      return { status: "error", message: "La demande de rendez-vous nécessite le réseau." };
    }
    try {
      const rdv = await apiCreateAppointment(payload);
      set((s) => ({ appointments: [rdv, ...s.appointments] }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  transitionAppointment: async (id, action, motif) => {
    try {
      const rdv = await apiTransitionAppointment(id, action, motif);
      set((s) => ({
        appointments: s.appointments.map((r) => (r.id === id ? rdv : r)),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  loadAppointments: async (patientId) => {
    if (!get().isOnline()) return;
    try {
      const appointments = await apiListAppointments(patientId);
      set((s) => ({ appointments }));
    } catch {
      /* miroir inchangé */
    }
  },

  loadStock: async () => {
    if (!get().isOnline()) return;
    try {
      const etat = await apiGetStock();
      set((s) => ({
        stockItems: etat.items,
        stockMouvements: etat.mouvements,
      }));
    } catch {
      /* miroir inchangé */
    }
  },

  createStockMouvement: async (payload) => {
    try {
      const item = await apiCreateStockMouvement(payload);
      set((s) => ({
        stockItems: s.stockItems.map((i) => (i.id === item.id ? item : i)),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  loadReferences: async () => {
    if (!get().isOnline()) return;
    try {
      const references = await apiListReferences();
      set((s) => ({ references }));
    } catch {
      /* miroir inchangé */
    }
  },

  createReference: async (payload) => {
    try {
      const fiche = await apiCreateReference(payload);
      set((s) => ({ references: [fiche, ...s.references] }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  transitionReference: async (id, action, resume) => {
    try {
      const fiche = await apiTransitionReference(id, action, resume);
      set((s) => ({ references: s.references.map((r) => (r.id === id ? fiche : r)) }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  loadSnis: async () => {
    if (!get().isOnline()) return;
    try {
      const snis = await apiGetSnis();
      set({ snis });
    } catch {
      /* miroir inchangé */
    }
  },

  loadAudit: async () => {
    if (!get().isOnline()) return;
    try {
      const auditEntries = await apiListAudit(150);
      set({ auditEntries });
    } catch {
      /* miroir inchangé */
    }
  },

  declareDeath: async (patientId, cause) => {
    try {
      await apiDeclareDeath(patientId, cause);
      set((s) => ({
        patients: s.patients.map((p) =>
          p.id === patientId
            ? { ...p, deceased: true, deceasedAt: new Date().toISOString(), causeDeces: cause }
            : p,
        ),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },

  cancelPrescription: async (id, kind, motif) => {
    try {
      // L'annulation logistique / l'erreur de saisie passent par le même
      // contrat que la dispensation : le statut + le motif.
      await fetch(`/api/v1/prescriptions/${id}`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          ...(typeof window !== "undefined" &&
            window.localStorage.getItem("ph.session.v2")
            ? {
                authorization: `Bearer ${(JSON.parse(window.localStorage.getItem("ph.session.v2")!) as { jeton: string }).jeton}`,
              }
            : {}),
        },
        body: JSON.stringify({
          action: "STATUS_CHANGE",
          status: kind,
          motif,
        }),
      });
      set((s) => ({
        prescriptions: s.prescriptions.map((r) =>
          r.id === id ? { ...r, status: kind } : r,
        ),
      }));
      return { status: "ok" };
    } catch (error) {
      return {
        status: "error",
        message: error instanceof Error ? error.message : "Erreur inconnue",
      };
    }
  },
}));

/* --------------------------- Helpers externes ------------------------- */

function mergeById<T extends { id: string }>(current: T[], incoming: T[]): T[] {
  if (incoming.length === 0) return current;
  const map = new Map(current.map((item) => [item.id, item]));
  for (const item of incoming) map.set(item.id, item);
  return Array.from(map.values());
}

/** Une ordonnance dont chaque ligne est couverte passe COMPLETED. */
function recomputeStatus(prescription: Prescription): Prescription {
  const net = netDispensed(prescription);
  const solded = prescription.items.every(
    (item) => (net.get(item.drug) ?? 0) >= item.quantity,
  );
  return {
    ...prescription,
    status: solded && prescription.status === "ACTIVE" ? "COMPLETED" : prescription.status,
  };
}

export function getPatientLabel(patient: Patient): string {
  return `${patient.name.family} ${patient.name.given}`;
}

export const DEVICE = DEVICE_LABEL;
