/**
 * PUBLIC HEALTH — serveur de démonstration en mémoire (routes /api/v1/*).
 *
 * Simule fidèlement les contrats du backend Spring Boot :
 *  - E1 : création patients, 409 avec candidats (matcher E1), 410 fusion ;
 *  - E2 : journal delta séquencé + idempotence par opId ;
 *  - E3 : ordonnances append-only, dispensation cumulée, contre-entrées ;
 *  - E4 : paiements 8 états forward-only, réconciliation nocturne ;
 *  - E6 : utilisateurs et structures.
 *
 * Les mutations passent toujours par applyDelta() qui alimente le journal
 * séquencé — c'est ce journal que GET /api/v1/sync/delta?cursor= sert.
 */

import type {
  AppointmentRecord,
  AuditEntryView,
  ConsultationRecord,
  DispenseEvent,
  HealthFacility,
  OperationAck,
  Patient,
  PaymentRecord,
  Prescription,
  ReferenceFiche,
  StaffUser,
  StockItem,
  StockMouvement,
} from "@/lib/types";
import { PAYMENT_TRANSITIONS } from "@/lib/types";
import { uuidV7 } from "@/lib/uuid";

export interface DeltaEntry {
  seq: number;
  at: string;
  kind: string;
  entity: Patient | Prescription | PaymentRecord;
}

interface DemoState {
  patients: Patient[];
  prescriptions: Prescription[];
  payments: PaymentRecord[];
  users: StaffUser[];
  facilities: HealthFacility[];
  deltaLog: DeltaEntry[];
  phSeqByYear: Map<number, number>;
  seenOpIds: Map<string, OperationAck>;
  bootedAt: string;
  /* V14 — correction audit de fidélité */
  consultations: ConsultationRecord[];
  appointments: AppointmentRecord[];
  stockItems: StockItem[];
  stockMouvements: StockMouvement[];
  references: ReferenceFiche[];
  auditLog: AuditEntryView[];
  /** OTP patients en attente : téléphone → code (5 min côté back). */
  patientOtp: Map<string, string>;
  /** Défis MFA en attente : email → code. */
  mfaChallenges: Map<string, string>;
}

let state: DemoState | null = null;

const iso = (d: Date) => d.toISOString();
const daysAgo = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return iso(d);
};

function patient(
  id: string,
  phReference: string,
  family: string,
  given: string,
  gender: "M" | "F",
  birthDate: string,
  phone: string | undefined,
  identifiers: Patient["identifiers"],
  facility: string,
  village: string | undefined,
  createdDaysAgo: number,
  extra: Partial<Patient> = {},
): Patient {
  return {
    id,
    phReference,
    name: { family, given },
    gender,
    birthDate,
    phone,
    identifiers,
    facility,
    village,
    active: true,
    version: 1,
    createdAt: daysAgo(createdDaysAgo),
    updatedAt: daysAgo(createdDaysAgo),
    ...extra,
  };
}

function init(): DemoState {
  const patients: Patient[] = [
    patient("p-001", "PH-2025-000112", "WÉDRAOGO", "Aïcha", "F", "1992-04-12", "70123456", [{ type: "NUNP", value: "BF-00091287" }], "CSPS Ouaga 12", "Ouagadougou, Secteur 12", 240),
    patient("p-002", "PH-2025-000131", "OUÉDRAOGO", "Issa", "M", "1985-09-03", "70456789", [{ type: "NUNP", value: "BF-00071245" }], "CSPS Ouaga 12", "Ouagadougou, Tanghin", 232),
    patient("p-003", "PH-2025-000158", "SAWADOGO", "Fatimata", "F", "1978-01-22", "76234512", [{ type: "CNIB", value: "B1234567" }], "CMA Kossodo", "Kossodo", 210),
    patient("p-004", "PH-2026-000004", "KABORÉ", "Moussa", "M", "2016-06-08", "70456789", [], "CSPS Ouaga 12", "Ouagadougou, Secteur 12", 90),
    patient("p-005", "PH-2026-000021", "TRAORÉ", "Salamata", "F", "1999-11-30", "66884422", [{ type: "NUNP", value: "BF-00103011" }], "CSPS Tanghin", "Tanghin", 64),
    patient("p-006", "PH-2024-000087", "ZONGO", "Boukary", "M", "1952-03-17", "71223344", [{ type: "NUNP", value: "BF-00053318" }], "CHU Yalgado Ouédraogo", "Ouagadougou, Gounghin", 400),
    patient("p-007", "PH-2026-000039", "COMPAORÉ", "Alice", "F", "1988-07-14", "76556677", [], "CMA Kossodo", "Saaba", 48),
    patient("p-008", "PH-2024-000122", "DABIRÉ", "Sylvain", "M", "1961-12-01", "70998877", [{ type: "CNIB", value: "B9876543" }], "CHR Ouahigouya", "Ouahigouya", 380),
    patient("p-009", "PH-2026-000052", "OUATTARA", "Mariam", "F", "2010-02-19", "74112233", [{ type: "NUNP", value: "BF-00114502" }], "CSPS Ouaga 12", "Ouagadougou, Zogona", 30),
    patient("p-010", "PH-2023-000241", "TAPSOBA", "Adama", "M", "1975-05-05", "70332211", [{ type: "NUNP", value: "BF-00042290" }], "CHU Yalgado Ouédraogo", "Ouagadougou, Patte d'Oie", 500),
    patient("p-011", "PH-2026-000067", "SANOU", "Estelle", "F", "1995-08-25", "65445566", [], "CMA Kossodo", "Bobo-Dioulasso (consult. externe)", 21),
    patient("p-012", "PH-2025-000203", "BATIONO", "Roger", "M", "1980-10-10", "76778899", [{ type: "CNIB", value: "B4567890" }], "CHR Ouahigouya", "Ouahigouya", 175),
    // Dossier fusionné : 410 Gone + masterId vers p-001 (contrat E1)
    patient("p-013", "PH-2025-000119", "KONE", "Rasmata", "F", "1992-04-12", "70123456", [{ type: "NUNP", value: "BF-00091287" }], "CSPS Ouaga 12", "Ouagadougou, Secteur 12", 239, { active: false, masterId: "p-001", version: 2 }),
  ];

  const prescriptions: Prescription[] = [
    {
      id: "r-001", patientId: "p-001", patientName: { family: "WÉDRAOGO", given: "Aïcha" },
      prescriber: "Dr. KIENDREBEOGO Jean", facility: "CSPS Ouaga 12", date: daysAgo(2),
      diagnosis: "Paludisme simple",
      items: [{ drug: "Artéméther/Luméfantrine", dosage: "20/120 mg", frequency: "2×/jour", durationDays: 3, quantity: 6 }],
      dispenses: [{ id: "d-001", at: daysAgo(2), pharmacist: "SANOU Estelle", source: "INTERNE", lines: [{ drug: "Artéméther/Luméfantrine", quantity: 4 }] }],
      status: "ACTIVE", createdAt: daysAgo(2),
    },
    {
      id: "r-002", patientId: "p-006", patientName: { family: "ZONGO", given: "Boukary" },
      prescriber: "Dr. BAMBARA Chantal", facility: "CHU Yalgado Ouédraogo", date: daysAgo(1),
      diagnosis: "Hypertension artérielle",
      items: [{ drug: "Amlodipine", dosage: "5 mg", frequency: "1×/jour", durationDays: 30, quantity: 30 }],
      dispenses: [],
      status: "ACTIVE", createdAt: daysAgo(1),
    },
    {
      id: "r-003", patientId: "p-009", patientName: { family: "OUATTARA", given: "Mariam" },
      prescriber: "Dr. KIENDREBEOGO Jean", facility: "CSPS Ouaga 12", date: daysAgo(9),
      diagnosis: "Infection respiratoire aiguë",
      items: [{ drug: "Amoxicilline", dosage: "500 mg", frequency: "3×/jour", durationDays: 7, quantity: 21 }],
      dispenses: [{ id: "d-003", at: daysAgo(9), pharmacist: "SANOU Estelle", source: "INTERNE", lines: [{ drug: "Amoxicilline", quantity: 21 }] }],
      status: "COMPLETED", createdAt: daysAgo(9),
    },
    {
      id: "r-004", patientId: "p-007", patientName: { family: "COMPAORÉ", given: "Alice" },
      prescriber: "Dr. OUEDRAOGO Pascal", facility: "CMA Kossodo", date: daysAgo(1),
      diagnosis: "Diarrhée aiguë",
      items: [
        { drug: "SRO", dosage: "sachet", frequency: "après chaque selle", durationDays: 3, quantity: 6 },
        { drug: "Sulfate de zinc", dosage: "20 mg", frequency: "1×/jour", durationDays: 10, quantity: 10 },
      ],
      dispenses: [{ id: "d-004", at: daysAgo(1), pharmacist: "KONE Ibrahim", source: "PRIVE", lines: [{ drug: "SRO", quantity: 3 }] }],
      status: "ACTIVE", createdAt: daysAgo(1),
    },
    {
      id: "r-005", patientId: "p-005", patientName: { family: "TRAORÉ", given: "Salamata" },
      prescriber: "Dr. BAMBARA Chantal", facility: "CSPS Tanghin", date: daysAgo(5),
      diagnosis: "Anémie ferriprive",
      items: [{ drug: "Fer + acide folique", dosage: "comprimé", frequency: "1×/jour", durationDays: 60, quantity: 60 }],
      dispenses: [{ id: "d-005", at: daysAgo(5), pharmacist: "SANOU Estelle", source: "INTERNE", lines: [{ drug: "Fer + acide folique", quantity: 20 }] }],
      status: "ACTIVE", createdAt: daysAgo(5),
    },
    {
      id: "r-006", patientId: "p-010", patientName: { family: "TAPSOBA", given: "Adama" },
      prescriber: "Dr. OUEDRAOGO Pascal", facility: "CHU Yalgado Ouédraogo", date: daysAgo(6),
      diagnosis: "Diabète type 2",
      items: [{ drug: "Metformine", dosage: "850 mg", frequency: "2×/jour", durationDays: 30, quantity: 60 }],
      // Dispensation erronée corrigée par contre-entrée (démo E3)
      dispenses: [
        { id: "d-006a", at: daysAgo(6), pharmacist: "KONE Ibrahim", source: "INTERNE", lines: [{ drug: "Metformine", quantity: 60 }] },
        { id: "d-006b", at: daysAgo(6), pharmacist: "KONE Ibrahim", source: "INTERNE", counterEntryOf: "d-006a", lines: [{ drug: "Metformine", quantity: 60 }] },
        { id: "d-006c", at: daysAgo(5), pharmacist: "KONE Ibrahim", source: "INTERNE", lines: [{ drug: "Metformine", quantity: 30 }] },
      ],
      status: "ACTIVE", createdAt: daysAgo(6),
    },
  ];

  const payments: PaymentRecord[] = [
    { id: "pay-001", clientRequestId: "cr-001", patientId: "p-001", patientName: { family: "WÉDRAOGO", given: "Aïcha" }, prescriptionId: "r-001", purpose: "Consultation générale", amountXof: 1000, channel: "MOBILE_MONEY", state: "RECONCILED", facility: "CSPS Ouaga 12", reconciledAt: daysAgo(1), createdAt: daysAgo(2), updatedAt: daysAgo(1) },
    { id: "pay-002", clientRequestId: "cr-002", patientId: "p-002", patientName: { family: "OUÉDRAOGO", given: "Issa" }, purpose: "Consultation générale", amountXof: 1000, channel: "ESPECES", state: "SUCCEEDED", facility: "CSPS Ouaga 12", createdAt: daysAgo(1), updatedAt: daysAgo(1) },
    { id: "pay-003", clientRequestId: "cr-003", patientId: "p-006", patientName: { family: "ZONGO", given: "Boukary" }, prescriptionId: "r-002", purpose: "Ordonnance (dispensation)", amountXof: 3500, channel: "MOBILE_MONEY", state: "PENDING", facility: "CHU Yalgado Ouédraogo", createdAt: daysAgo(1), updatedAt: daysAgo(1) },
    { id: "pay-004", clientRequestId: "cr-004", patientId: "p-009", patientName: { family: "OUATTARA", given: "Mariam" }, purpose: "Examens de laboratoire", amountXof: 2000, channel: "MOBILE_MONEY", state: "SUCCEEDED", facility: "CSPS Ouaga 12", createdAt: daysAgo(9), updatedAt: daysAgo(9) },
    { id: "pay-005", clientRequestId: "cr-005", patientId: "p-007", patientName: { family: "COMPAORÉ", given: "Alice" }, purpose: "Consultation générale", amountXof: 1000, channel: "CARTE", state: "FAILED", facility: "CMA Kossodo", createdAt: daysAgo(1), updatedAt: daysAgo(1) },
    { id: "pay-006", clientRequestId: "cr-006", patientId: "p-005", patientName: { family: "TRAORÉ", given: "Salamata" }, purpose: "Hospitalisation (jour)", amountXof: 12000, channel: "ESPECES", state: "RECONCILED", facility: "CSPS Tanghin", reconciledAt: daysAgo(3), createdAt: daysAgo(5), updatedAt: daysAgo(3) },
    { id: "pay-007", clientRequestId: "cr-007", patientId: "p-010", patientName: { family: "TAPSOBA", given: "Adama" }, prescriptionId: "r-006", purpose: "Ordonnance (dispensation)", amountXof: 3500, channel: "MOBILE_MONEY", state: "SUCCEEDED", facility: "CHU Yalgado Ouédraogo", createdAt: daysAgo(5), updatedAt: daysAgo(5) },
    { id: "pay-008", clientRequestId: "cr-008", patientId: "p-011", patientName: { family: "SANOU", given: "Estelle" }, purpose: "Consultation générale", amountXof: 1000, channel: "MOBILE_MONEY", state: "INITIATED", facility: "CMA Kossodo", createdAt: daysAgo(0), updatedAt: daysAgo(0) },
  ];

  const users: StaffUser[] = [
    { id: "u-001", email: "infirmier@demo.bf", fullName: "Aminata Sawadogo", role: "INFIRMIER", facility: "CSPS Ouaga 12", mfaEnabled: false, active: true, lastSeenAt: daysAgo(0) },
    { id: "u-002", email: "pharmacien@demo.bf", fullName: "Estelle Sanou", role: "PHARMACIEN", facility: "CMA Kossodo", mfaEnabled: false, active: true, lastSeenAt: daysAgo(0) },
    { id: "u-003", email: "medecin@demo.bf", fullName: "Jean Kiendrebeogo", role: "MEDECIN", facility: "CSPS Ouaga 12", mfaEnabled: false, active: true, lastSeenAt: daysAgo(1) },
    { id: "u-004", email: "pharmacien2@demo.bf", fullName: "Ibrahim Kone", role: "PHARMACIEN", facility: "CHU Yalgado Ouédraogo", mfaEnabled: true, active: true, lastSeenAt: daysAgo(1) },
    { id: "u-005", email: "caissier@demo.bf", fullName: "Sylvie Bationo", role: "AGENT_FINANCIER", facility: "CMA Kossodo", mfaEnabled: false, active: true, lastSeenAt: daysAgo(0) },
    { id: "u-006", email: "medecin2@demo.bf", fullName: "Pascal Ouedraogo", role: "MEDECIN", facility: "CMA Kossodo", mfaEnabled: false, active: true, lastSeenAt: daysAgo(2) },
    { id: "u-007", email: "superviseur@demo.bf", fullName: "Chantal Bambara", role: "SUPERVISEUR", facility: "DRS Centre", mfaEnabled: true, active: true, lastSeenAt: daysAgo(3) },
    { id: "u-008", email: "admin@demo.bf", fullName: "Roger Compaore", role: "ADMIN", facility: "DRS Centre", mfaEnabled: true, active: true, lastSeenAt: daysAgo(0) },
  ];

  const facilities: HealthFacility[] = [
    { id: "f-1", name: "CSPS Ouaga 12", kind: "CSPS", region: "Centre", staffCount: 9, online: true },
    { id: "f-2", name: "CSPS Tanghin", kind: "CSPS", region: "Centre", staffCount: 7, online: true },
    { id: "f-3", name: "CMA Kossodo", kind: "CMA", region: "Centre", staffCount: 18, online: true },
    { id: "f-4", name: "CHR Ouahigouya", kind: "CHR", region: "Nord", staffCount: 42, online: false },
    { id: "f-5", name: "CHU Yalgado Ouédraogo", kind: "CHU", region: "Centre", staffCount: 210, online: true },
  ];

  // Journal delta initial : les entités seed sont servies au premier pull.
  const deltaLog: DeltaEntry[] = [];
  let seq = 0;
  const push = (kind: string, entity: DeltaEntry["entity"]) => {
    deltaLog.push({ seq: ++seq, at: entity.createdAt ?? iso(new Date()), kind, entity });
  };
  patients.filter((p) => p.active).forEach((p) => push("patient.snapshot", p));
  prescriptions.forEach((r) => push("prescription.snapshot", r));
  payments.forEach((m) => push("payment.snapshot", m));

  const phSeqByYear = new Map<number, number>();
  phSeqByYear.set(2026, 67);

  // ---------------------------------------------------------------
  // V14 — consultation (l'acte clinique PERSISTÉ), RDV, stock,
  // référence : semés pour que les nouvelles vues vivent.
  // ---------------------------------------------------------------
  const consultations: ConsultationRecord[] = [
    {
      id: "c-001", patientId: "p-001", facility: "CSPS Ouaga 12",
      practitioner: "Aminata Sawadogo", motif: "Fièvre et céphalées depuis 3 jours",
      diagnosticCode: "B54", diagnosticLabel: "Paludisme à P. falciparum",
      notes: "TDR positif. Patient couché, prostration légère.",
      constantes: { taSystolique: 110, taDiastolique: 70, temperatureC: 38.9, poidsKg: 58 },
      examens: [{ id: "e-001", type: "tdr_paludisme", statut: "resultat", resultat: "TDR positif", positif: true }],
      date: daysAgo(9),
    },
    {
      id: "c-002", patientId: "p-002", facility: "CSPS Ouaga 12",
      practitioner: "Jean Kiendrebeogo", motif: "Toux et difficulté respiratoire",
      diagnosticCode: "J06", diagnosticLabel: "Infection respiratoire aiguë",
      notes: "Sibilants diffus, pas de signe de gravité.",
      constantes: { temperatureC: 37.8, poidsKg: 9 },
      examens: [],
      date: daysAgo(4),
    },
    {
      id: "c-003", patientId: "p-004", facility: "CMA Kossodo",
      practitioner: "Pascal Ouedraogo", motif: "Contrôle tension artérielle",
      diagnosticCode: "I10", diagnosticLabel: "Hypertension artérielle essentielle",
      constantes: { taSystolique: 165, taDiastolique: 95, poidsKg: 78 },
      examens: [],
      date: daysAgo(2),
    },
  ];

  const appointments: AppointmentRecord[] = [
    {
      id: "rdv-001", patientId: "p-002", structure: "CSPS Ouaga 12", type: "controle",
      creneau: hoursFromNow(26), statut: "confirme", motif: "Contrôle après traitement IRA",
      demandePar: "agent", createdAt: daysAgo(1),
    },
    {
      id: "rdv-002", patientId: "p-003", structure: "CMA Kossodo", type: "cpn",
      creneau: hoursFromNow(48), statut: "demande", motif: "CPN 3 — grossesse 28 SA",
      demandePar: "patient", createdAt: daysAgo(0),
    },
    {
      id: "rdv-003", patientId: "p-009", structure: "CSPS Ouaga 12", type: "vaccination",
      creneau: daysAgoDate(2), statut: "honore", motif: "PEV — rappel Penta 3",
      demandePar: "agent", createdAt: daysAgo(6),
    },
  ];

  const stockItems: StockItem[] = [
    { id: "s-001", structure: "CSPS Ouaga 12", medicationCode: "AL-ACT", medicationLabel: "Artéméther-Luméfantrine 20/120", quantity: 240, seuilAlerte: 50 },
    { id: "s-002", structure: "CSPS Ouaga 12", medicationCode: "PARA-500", medicationLabel: "Paracétamol 500 mg", quantity: 0, seuilAlerte: 100 },
    { id: "s-003", structure: "CSPS Ouaga 12", medicationCode: "AMOX-500", medicationLabel: "Amoxicilline 500 mg", quantity: 85, seuilAlerte: 40 },
    { id: "s-004", structure: "CSPS Ouaga 12", medicationCode: "SRO", medicationLabel: "SRO sachet", quantity: 30, seuilAlerte: 60 },
    { id: "s-005", structure: "CMA Kossodo", medicationCode: "AL-ACT", medicationLabel: "Artéméther-Luméfantrine 20/120", quantity: 610, seuilAlerte: 100 },
  ];

  const stockMouvements: StockMouvement[] = [
    { id: "m-001", medicationCode: "PARA-500", type: "reception", quantity: 500, motif: "Réception trimestrielle CAMEG", date: daysAgo(30) },
    { id: "m-002", medicationCode: "PARA-500", type: "dispensation", quantity: 500, motif: "Dispensations cumulées", date: daysAgo(0) },
    { id: "m-003", medicationCode: "AL-ACT", type: "reception", quantity: 300, motif: "Réception CSD", date: daysAgo(15) },
  ];

  const references: ReferenceFiche[] = [
    {
      id: "ref-001", patientId: "p-001", structureOrigine: "CSPS Ouaga 12",
      structureDestination: "CHU Yalgado Ouédraogo",
      motif: "Paludisme grave — anémie sévère", urgence: true, statut: "envoyee",
      createdAt: daysAgo(1),
    },
    {
      id: "ref-002", patientId: "p-004", structureOrigine: "CMA Kossodo",
      structureDestination: "CHU Yalgado Ouédraogo",
      motif: "HTA compliquée — bilan rénal", urgence: false, statut: "retournee",
      contreReference: "Bilan rénal normal. Poursuivre amlodipine 5 mg, contrôle à 1 mois.",
      createdAt: daysAgo(12), recueLe: daysAgo(11),
    },
  ];

  const auditLog: AuditEntryView[] = [
    { date: daysAgo(0), acteur: "u-001", action: "PATIENT_READ", entite: "patient", entiteId: "p-001", resultat: "SUCCESS" },
    { date: daysAgo(0), acteur: "u-008", action: "AUTH_LOGIN", entite: "utilisateur", entiteId: "u-008", motif: "connexion interne HS256", resultat: "SUCCESS" },
    { date: daysAgo(1), acteur: "u-002", action: "DISPENSATION_RECORDED", entite: "dispensation", motif: "AL-ACT 30", resultat: "SUCCESS" },
    { date: daysAgo(1), acteur: "u-008", action: "PERMISSION_DENIED", entite: "api", motif: "GET /api/v1/admin/users : La permission admin:gerer est requise (rôle medecin)", resultat: "DENIED" },
    { date: daysAgo(2), acteur: "u-007", action: "STOCK_ALERTE_SEUIL", entite: "stock", motif: "SRO sous le seuil (30)", resultat: "SUCCESS" },
  ];

  return {
    patients, prescriptions, payments, users, facilities, deltaLog,
    phSeqByYear, seenOpIds: new Map(), bootedAt: iso(new Date()),
    consultations, appointments, stockItems, stockMouvements, references,
    auditLog, patientOtp: new Map(), mfaChallenges: new Map(),
  };
}

function hoursFromNow(h: number) {
  const d = new Date();
  d.setHours(d.getHours() + h);
  return d.toISOString();
}
function daysAgoDate(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString();
}

export function getState(): DemoState {
  if (!state) state = init();
  return state;
}

/** PH-AAAA-NNNNNN : séquence annuelle atomique (simulée). */
export function nextPhReference(): string {
  const s = getState();
  const year = new Date().getUTCFullYear();
  const next = (s.phSeqByYear.get(year) ?? 0) + 1;
  s.phSeqByYear.set(year, next);
  return `PH-${year}-${String(next).padStart(6, "0")}`;
}

/** Toute mutation serveur alimente le journal delta (le pull s'en sert). */
export function applyDelta(
  kind: string,
  entity: Patient | Prescription | PaymentRecord,
): DeltaEntry {
  const s = getState();
  const entry: DeltaEntry = {
    seq: s.deltaLog.length + 1,
    at: iso(new Date()),
    kind,
    entity,
  };
  s.deltaLog.push(entry);
  return entry;
}

export function getDelta(cursor: number): {
  cursor: number;
  entries: DeltaEntry[];
} {
  const s = getState();
  const entries = s.deltaLog.filter((e) => e.seq > cursor);
  return { cursor: s.deltaLog.length, entries };
}

/** Idempotence sync : rejeu d'un opId déjà vu → duplicate. */
export function ackOperation(opId: string, compute: () => OperationAck): OperationAck {
  const s = getState();
  const existing = s.seenOpIds.get(opId);
  if (existing) return existing;
  const ack = compute();
  s.seenOpIds.set(opId, ack);
  return ack;
}

export function findPatient(id: string): Patient | undefined {
  return getState().patients.find((p) => p.id === id);
}

export function addPatient(p: Patient): void {
  getState().patients.push(p);
}

export function findPrescription(id: string): Prescription | undefined {
  return getState().prescriptions.find((r) => r.id === id);
}

export function addPrescription(r: Prescription): void {
  getState().prescriptions.push(r);
}

export function addDispense(prescriptionId: string, event: DispenseEvent): void {
  findPrescription(prescriptionId)?.dispenses.push(event);
}

export function findPayment(id: string): PaymentRecord | undefined {
  return getState().payments.find((m) => m.id === id);
}

export function addPayment(m: PaymentRecord): void {
  getState().payments.push(m);
}

/** Transition forward-only ; lève si interdite (contrat E4). */
export function transitionPayment(id: string, target: PaymentRecord["state"]): void {
  const m = findPayment(id);
  if (!m) throw new Error(`Paiement ${id} introuvable`);
  const allowed = PAYMENT_TRANSITIONS[m.state];
  if (!allowed.includes(target)) {
    throw new Error(
      `Transition ${m.state} → ${target} interdite (forward-only)`,
    );
  }
  m.state = target;
  m.updatedAt = iso(new Date());
  if (target === "RECONCILED") m.reconciledAt = m.updatedAt;
}

export function newEntityId(): string {
  return uuidV7();
}

/** Réconciliation nocturne simulée : les SUCCEEDED passent RECONCILED,
 *  les FAILED/INITIATED orphelins restent visibles pour la revue. */
export function runNightlyReconciliation(): number {
  const s = getState();
  let count = 0;
  for (const m of s.payments) {
    if (m.state === "SUCCEEDED") {
      transitionPayment(m.id, "RECONCILED");
      applyDelta("payment.reconciled", m);
      count++;
    }
  }
  return count;
}
