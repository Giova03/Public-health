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
  | "caisse"
  | "sync"
  | "backoffice"
  | "appointments"
  | "stock"
  | "references"
  | "statistics"
  | "audit";

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
  /** V14 (I15) : décès déclaré — le dossier est scellé. */
  deceased?: boolean;
  deceasedAt?: string;
  causeDeces?: string;
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

/** Réponse 409 ProblemDetail — candidats EMBARQUÉS ou MASQUÉS (S4/Q42). */
export interface ConflictPatientBody {
  title: string;
  detail: string;
  status: 409;
  /** Candidats complets — uniquement pour un appelant portant patient:lire. */
  candidates?: DuplicateCandidate[];
  /** Contrat masqué (anonyme / rôle sans patient:lire) : compteur seul. */
  candidatesRedacted?: boolean;
  candidatesCount?: number;
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

export type PrescriptionStatus = "ACTIVE" | "COMPLETED" | "CANCELLED" | "ENTERED_IN_ERROR";

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
  | "AGENT_FINANCIER"
  | "SUPERVISEUR"
  | "ADMIN";

export interface StaffUser {
  id: string;
  fullName: string;
  role: StaffRole;
  facility: string;
  /** Compte de connexion (V14 : l'auth est RÉELLE, BCrypt côté back). */
  email?: string;
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

/* ------------------------------------------------------------------ */
/* V14 — Correction audit de fidélité : consultation, RDV, labo,      */
/* stock, référence, statistiques SNIS, audit                          */
/* ------------------------------------------------------------------ */

/** Constantes vitales d'une consultation (I4 : désormais PERSISTÉES). */
export interface ConsultationConstantes {
  taSystolique?: number;
  taDiastolique?: number;
  temperatureC?: number;
  poidsKg?: number;
}

/** Consultation complète — l'acte clinique n'est plus réduit à l'ordonnance. */
export interface ConsultationRecord {
  id: string;
  patientId: string;
  facility: string;
  practitioner: string;
  motif: string;
  diagnosticCode: string;
  diagnosticLabel: string;
  notes?: string;
  constantes: ConsultationConstantes;
  /** Examens de laboratoire liés (TDR…) — la preuve derrière le diagnostic. */
  examens: { id: string; type: string; statut: string; resultat?: string; positif?: boolean }[];
  date: string;
  pendingSync?: boolean;
}

/** Rendez-vous — machine à états demande→confirme→honoré/annulé/absent. */
export type AppointmentStatus = "demande" | "confirme" | "honore" | "annule" | "absent";
export type AppointmentType = "general" | "cpn" | "vaccination" | "controle" | "suivi";

export interface AppointmentRecord {
  id: string;
  patientId: string;
  patientName?: PatientName;
  structure: string;
  type: AppointmentType;
  creneau: string; // ISO
  statut: AppointmentStatus;
  motif?: string;
  demandePar: "patient" | "agent";
  motifAnnulation?: string;
  createdAt: string;
}

/** Ligne de stock (I8) — la dispensation décrémente, la rupture est visible. */
export interface StockItem {
  id: string;
  structure: string;
  medicationCode: string;
  medicationLabel: string;
  quantity: number;
  seuilAlerte: number;
}

/* ------------------------------------------------------------------ */
/* I5 — Frais d'accès : le ticket AVANT la consultation                */
/* ------------------------------------------------------------------ */

/** Machine forward-only : en_attente → paye | exonere (terminaux). */
export type FraisAccesStatut = "en_attente" | "paye" | "exonere";

/** Natures d'exonération décidées à la caisse (miroir du CHECK V15). */
export type ExonerationNature =
  | "indigent_atteste"
  | "enfant_moins_5_ans"
  | "cesarienne"
  | "grossesse_suivie";

export const EXONERATION_NATURES: { value: ExonerationNature; label: string }[] = [
  { value: "indigent_atteste", label: "Indigent attesté" },
  { value: "enfant_moins_5_ans", label: "Enfant de moins de 5 ans" },
  { value: "cesarienne", label: "Césarienne" },
  { value: "grossesse_suivie", label: "Grossesse suivie (CPN)" },
];

/** Ticket d'accès — un par patient × structure × jour (idempotent). */
export interface FraisAccesTicket {
  id: string;
  patientId: string;
  patientName?: PatientName;
  structure: string;
  statut: FraisAccesStatut;
  montantXof: number;
  /** Exonération TRACÉE : nature + motif obligatoires (NULL sinon). */
  exonerationNature?: ExonerationNature;
  exonerationMotif?: string;
  exonerationDecideePar?: string;
  encaissePar?: string;
  encaisseLe?: string;
  ouvertPar: string;
  createdAt: string;
}

export type StockMouvementType = "reception" | "dispensation" | "contre_entree" | "ajustement";

export interface StockMouvement {
  id: string;
  medicationCode: string;
  type: StockMouvementType;
  quantity: number;
  motif?: string;
  date: string;
}

/** Référence / contre-référence (I7) — la pyramide sanitaire tracée. */
export type ReferenceStatus = "envoyee" | "recue" | "hospitalisee" | "retournee" | "cloturee";

export interface ReferenceFiche {
  id: string;
  patientId: string;
  patientName?: PatientName;
  structureOrigine: string;
  structureDestination: string;
  motif: string;
  urgence: boolean;
  statut: ReferenceStatus;
  contreReference?: string;
  createdAt: string;
  recueLe?: string;
}

/** Entrée du journal d'audit (I16) — la chaîne existait, l'écran manquait. */
export interface AuditEntryView {
  date: string;
  acteur: string;
  action: string;
  entite: string;
  entiteId?: string;
  motif?: string;
  resultat: string;
}

/** Statistiques SNIS mensuelles (I11) — la donnée remonte. */
export interface SnisStats {
  periode: string;
  consultations: { total: number; moinsDe5: number; de5a14: number; femmes15a49: number };
  paludismeConfirme: number;
  diagnostics: { code: string; total: number }[];
  ordonnances: number;
  dispensations: number;
  paiements: { inities: number; encaisseXof: number; echecs: number };
  references: { envoyees: number; nonAbouties48h: number };
  rendezVous: { demandes: number; honores: number; annules: number };
  deces: number;
  rupturesStock: number;
}

/** Exonération (I5) : la caisse réelle du BF — indigents, < 5 ans, césariennes. */
export type ExonerationKind =
  | "AUCUNE"
  | "INDIGENT_ATTESTE"
  | "ENFANT_MOINS_5_ANS"
  | "CESARIENNE"
  | "GROSSESSE_SUIVIE";
