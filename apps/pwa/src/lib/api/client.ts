/**
 * Client HTTP typé vers l'API PUBLIC HEALTH (module identity, épique E1).
 *
 * Contrats couverts (vérifiés dans PatientController + GlobalExceptionHandler) :
 * - POST /api/v1/patients — création idempotente (clientRequestId UUID v7),
 *   409 = contrat UX (ADR-003) : les candidats voyagent dans la réponse.
 * - GET  /api/v1/patients?family=&given=&phone=&birthDate= — recherche miroir.
 * - GET  /api/v1/patients/{id} — dossier doré, 410 si fusionné (+ masterId).
 * - GET  /api/v1/identity/merge-log — journal des fusions.
 *
 * Règles : TypeScript strict, aucune donnée sensible dans les logs,
 * timeouts honnêtes (AbortController), erreurs typées exploitables.
 */

// ------------------------------------------------------------------
// Types du contrat (miroir exact des DTO Java PatientDtos)
// ------------------------------------------------------------------

/** Nom du patient — use : « official » ou « usual » (FHIR). */
export interface NomPatient {
  use: string;
  family: string;
  given: string;
}

/** Coordonnée — system : « phone » ou « email ». */
export interface TelecomPatient {
  system: string;
  value: string;
  use: string | null;
}

/** Identifiant national — system : NUNP, CNIB, ANCIEN_REGISTRE ou LOCAL. */
export interface IdentifiantPatient {
  system: string;
  value: string;
}

/** Dossier patient (réponse PatientResponse du contrôleur). */
export interface Patient {
  id: string;
  phReference: string;
  active: boolean;
  gender: string;
  birthDate: string | null;
  birthDateApproximative: boolean;
  masterId: string | null;
  version: number;
  names: NomPatient[];
  telecoms: TelecomPatient[];
  identifiers: IdentifiantPatient[];
  createdAt: string;
}

/**
 * Candidat doublon embarqué dans le 409 (PatientDuplicateException.Candidate).
 * Champs réels : id, phReference, family, given, birthDate, gender,
 * score, method, blocking — le verdict se déduit de « blocking ».
 */
export interface CandidatDoublon {
  id: string;
  phReference: string;
  family: string;
  given: string;
  birthDate: string | null;
  gender: string;
  score: number;
  method: string;
  blocking: boolean;
}

/** Corps RFC 7807 renvoyé par GlobalExceptionHandler. */
interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}

/** 409 doublons — LA charge du contrat UX : les candidats + leurs scores. */
export interface ProblemDetail409 extends ProblemDetail {
  candidates?: CandidatDoublon[];
}

/** 410 dossier fusionné — le consommateur suit le maître. */
export interface ProblemDetail410 extends ProblemDetail {
  masterId?: string;
}

/** Charge de création (CreatePatientRequest). */
export interface RequeteCreationPatient {
  /** UUID v7 client — clé d'idempotence offline (rejeu = même dossier). */
  clientRequestId: string;
  forceCreate: boolean;
  /** Dossier candidat rejeté lors du « créer quand même » (tracé en audit). */
  duplicateOfRejected?: string;
  gender: string;
  birthDate: string;
  birthDateApproximative: boolean;
  names: NomPatient[];
  telecoms: TelecomPatient[];
  identifiers: IdentifiantPatient[];
}

/** Critères de recherche miroir (tous optionnels). */
export interface CritereRecherche {
  family?: string;
  given?: string;
  phone?: string;
  birthDate?: string;
}

/** Entrée du journal des fusions (MergeLogResponse). */
export interface EntreeJournalFusion {
  id: string;
  masterId: string;
  mergedId: string;
  reason: string;
  performedBy: string | null;
  performedAt: string;
}

// ------------------------------------------------------------------
// Erreurs typées — exploitables par les écrans, jamais opaques
// ------------------------------------------------------------------

/** 409 : doublon probable, la décision appartient à l'agent de santé. */
export class ErreurDoublons409 extends Error {
  readonly candidats: CandidatDoublon[];
  constructor(candidats: CandidatDoublon[], detail: string) {
    super(detail);
    this.name = "ErreurDoublons409";
    this.candidats = candidats;
  }
}

/** 410 : dossier fusionné — rediriger vers le maître. */
export class ErreurFusion410 extends Error {
  readonly masterId: string | null;
  constructor(masterId: string | null, detail: string) {
    super(detail);
    this.name = "ErreurFusion410";
    this.masterId = masterId;
  }
}

/** Erreur applicative signalée par l'API (RFC 7807). */
export class ErreurApi extends Error {
  readonly statut: number;
  readonly titre: string;
  constructor(statut: number, titre: string, detail: string) {
    super(detail);
    this.name = "ErreurApi";
    this.statut = statut;
    this.titre = titre;
  }
}

/** Réseau indisponible ou délai dépassé — personne n'est trompé. */
export class ErreurReseau extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ErreurReseau";
  }
}

// ------------------------------------------------------------------
// Configuration
// ------------------------------------------------------------------

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080";

/** Délai maximal honnête : au-delà, on déclare l'API injoignable. */
const DELAI_MAX_MS = 12_000;

// ------------------------------------------------------------------
// Mécanique interne
// ------------------------------------------------------------------

async function appelJson<T>(chemin: string, init?: RequestInit): Promise<T> {
  const controleur = new AbortController();
  const minuteur = setTimeout(() => controleur.abort(), DELAI_MAX_MS);
  let reponse: Response;
  try {
    reponse = await fetch(`${BASE_URL}${chemin}`, {
      ...init,
      signal: controleur.signal,
      headers: {
        Accept: "application/json",
        ...(init?.body ? { "Content-Type": "application/json" } : {}),
        ...init?.headers,
      },
    });
  } catch {
    // Erreurs réseau/timeout : message générique, aucune donnée exposée.
    throw new ErreurReseau(
      "API injoignable ou délai dépassé — vérifiez la connexion.",
    );
  } finally {
    clearTimeout(minuteur);
  }

  if (reponse.ok) {
    return (await reponse.json()) as T;
  }

  // Le corps peut être vide ou non-JSON : on dégrade proprement.
  let problem: ProblemDetail | null = null;
  try {
    problem = (await reponse.json()) as ProblemDetail;
  } catch {
    problem = null;
  }

  if (reponse.status === 409) {
    const charge = problem as ProblemDetail409 | null;
    throw new ErreurDoublons409(
      charge?.candidates ?? [],
      problem?.detail ?? "Patient probablement déjà enregistré.",
    );
  }
  if (reponse.status === 410) {
    const charge = problem as ProblemDetail410 | null;
    throw new ErreurFusion410(
      charge?.masterId ?? null,
      problem?.detail ?? "Dossier fusionné.",
    );
  }

  throw new ErreurApi(
    reponse.status,
    problem?.title ?? `Erreur ${reponse.status}`,
    problem?.detail ?? (reponse.statusText || "Erreur inattendue de l'API."),
  );
}

function construireQuery(criteres: CritereRecherche): string {
  const params = new URLSearchParams();
  if (criteres.family) params.set("family", criteres.family);
  if (criteres.given) params.set("given", criteres.given);
  if (criteres.phone) params.set("phone", criteres.phone);
  if (criteres.birthDate) params.set("birthDate", criteres.birthDate);
  const query = params.toString();
  return query ? `?${query}` : "";
}

// ------------------------------------------------------------------
// API identity — épique E1
// ------------------------------------------------------------------

/**
 * Créer un dossier patient. Idempotente par clientRequestId.
 * Sur doublon probable : lève ErreurDoublons409 avec les candidats —
 * c'est le cœur du contrat UX, l'écran DOIT les montrer à l'agent.
 */
export async function creerPatient(
  requete: RequeteCreationPatient,
): Promise<Patient> {
  return appelJson<Patient>("/api/v1/patients", {
    method: "POST",
    body: JSON.stringify(requete),
  });
}

/** Recherche miroir — mêmes critères que la détection à la création. */
export async function rechercherPatients(
  criteres: CritereRecherche,
): Promise<Patient[]> {
  return appelJson<Patient[]>(`/api/v1/patients${construireQuery(criteres)}`);
}

/** Dossier doré. Lève ErreurFusion410 (410) si le dossier a fusionné. */
export async function chargerPatient(id: string): Promise<Patient> {
  return appelJson<Patient>(`/api/v1/patients/${encodeURIComponent(id)}`);
}

/** Journal des fusions — traçabilité interne (audit). */
export async function listerJournalFusions(): Promise<EntreeJournalFusion[]> {
  return appelJson<EntreeJournalFusion[]>("/api/v1/identity/merge-log");
}
