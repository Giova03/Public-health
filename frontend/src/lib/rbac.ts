/**
 * PUBLIC HEALTH — matrice RBAC V14 (miroir front du backend).
 *
 * Source de vérité : `RolesPermissions.java` (domaine pur) et la migration
 * V14 qui sème `administration.role_permission` à l'identique — l'égalité
 * table/domaine est verrouillée par BackofficeIT. Ce fichier est le miroir
 * côté front.
 *
 * DEPUIS V14, la matrice N'EST PLUS DÉCORATIVE :
 *  - côté BACK, `FiltrePermissions` exige la permission de chaque route ;
 *  - côté FRONT (mode démo), les routes mock `/api/v1/**` vérifient le
 *    jeton et la permission (401/403), et les vues masquent/refusent
 *    les actions hors périmètre (roleHasPermission).
 * L'infériorité d'hier (« déclarée mais jamais appliquée ») est corrigée.
 */

import type { StaffRole, ViewId } from "@/lib/types";

export type BackendRoleCode =
  | "admin"
  | "medecin"
  | "infirmier"
  | "pharmacien"
  | "agent_financier"
  | "superviseur"
  | "agent_saisie";

export type PermissionCode =
  | "patient:lire"
  | "patient:ecrire"
  | "consultation:lire"
  | "consultation:ecrire"
  | "prescription:lire"
  | "prescription:ecrire"
  | "dispenser"
  | "paiement:initier"
  | "paiement:lire"
  | "paiement:reconcilier"
  | "stock:gerer"
  | "rendezvous:gerer"
  | "reference:gerer"
  | "laboratoire:ecrire"
  | "audit:lire"
  | "admin:gerer";

export interface BackendRole {
  code: BackendRoleCode;
  label: string;
  mission: string;
  demoStaffRole: StaffRole;
}

/** Les SEPT rôles du CHECK V14 — nomenclature alignée front = back. */
export const BACKEND_ROLES: BackendRole[] = [
  {
    code: "admin",
    label: "Administrateur",
    mission: "Back-office total : structures, comptes, rôles, réconciliation, SNIS.",
    demoStaffRole: "ADMIN",
  },
  {
    code: "medecin",
    label: "Médecin",
    mission: "Clinical complet : consultation, prescription, référence, laboratoire.",
    demoStaffRole: "MEDECIN",
  },
  {
    code: "infirmier",
    label: "Infirmier (ICP)",
    mission:
      "Le BUNDLE complet du CSPS réel : admission, consultation, dispensation, caisse, RDV, référence, labo.",
    demoStaffRole: "INFIRMIER",
  },
  {
    code: "pharmacien",
    label: "Pharmacien",
    mission: "Dispensation adossée au stock : comptoir, ruptures, réceptions COCOM.",
    demoStaffRole: "PHARMACIEN",
  },
  {
    code: "agent_financier",
    label: "Agent financier (caisse)",
    mission: "Ticket d'accès, exonérations, encaissements, suivi des paiements.",
    demoStaffRole: "AGENT_FINANCIER",
  },
  {
    code: "superviseur",
    label: "Superviseur",
    mission: "Supervision : audit, statistiques SNIS, références, paiements.",
    demoStaffRole: "SUPERVISEUR",
  },
  {
    code: "agent_saisie",
    label: "Agent de saisie",
    mission: "Registre : admission MPI uniquement.",
    demoStaffRole: "AGENT_SAISIE",
  },
];

export interface PermissionInfo {
  code: PermissionCode;
  label: string;
  description: string;
}

export const RBAC_PERMISSIONS: PermissionInfo[] = [
  { code: "patient:lire", label: "Lire le MPI", description: "Recherche et consultation des dossiers patients." },
  { code: "patient:ecrire", label: "Écrire le MPI", description: "Création de dossiers, forçage après doublon." },
  { code: "consultation:lire", label: "Lire les consultations", description: "Historique clinique des patients." },
  { code: "consultation:ecrire", label: "Écrire les consultations", description: "Motif, constantes, diagnostic, notes — l'acte clinique complet." },
  { code: "prescription:lire", label: "Lire les ordonnances", description: "Dossier pharmacologique." },
  { code: "prescription:ecrire", label: "Écrire les ordonnances", description: "Émettre et annuler des prescriptions." },
  { code: "dispenser", label: "Dispenser", description: "Dispensation au comptoir, contre-entrées." },
  { code: "paiement:initier", label: "Initier un paiement", description: "Ticket d'accès, encaissement, exonération." },
  { code: "paiement:lire", label: "Lire les paiements", description: "Suivi et facturation." },
  { code: "paiement:reconcilier", label: "Réconcilier", description: "Le run nocturne qui fait foi." },
  { code: "stock:gerer", label: "Gérer le stock", description: "Réceptions COCOM, inventaires, ruptures." },
  { code: "rendezvous:gerer", label: "Gérer les RDV", description: "Confirmer, honorer, annuler, convoquer." },
  { code: "reference:gerer", label: "Gérer les références", description: "Référence/contre-référence, table des non-abouties." },
  { code: "laboratoire:ecrire", label: "Écrire le laboratoire", description: "TDR et résultats d'examens." },
  { code: "audit:lire", label: "Lire l'audit", description: "Journal des accès, statistiques SNIS." },
  { code: "admin:gerer", label: "Administrer", description: "Structures, utilisateurs, rôles, matrice." },
];

/** La matrice 7 × 16 — miroir EXACT de V14/RolesPermissions.java. */
export const RBAC_MATRIX: Record<BackendRoleCode, PermissionCode[]> = {
  admin: [
    "patient:lire", "patient:ecrire",
    "consultation:lire", "consultation:ecrire",
    "prescription:lire", "prescription:ecrire", "dispenser",
    "paiement:initier", "paiement:lire", "paiement:reconcilier",
    "stock:gerer", "rendezvous:gerer", "reference:gerer", "laboratoire:ecrire",
    "audit:lire", "admin:gerer",
  ],
  medecin: [
    "patient:lire", "patient:ecrire",
    "consultation:lire", "consultation:ecrire",
    "prescription:lire", "prescription:ecrire",
    "rendezvous:gerer", "reference:gerer", "laboratoire:ecrire",
  ],
  infirmier: [
    "patient:lire", "patient:ecrire",
    "consultation:lire", "consultation:ecrire",
    "prescription:lire", "prescription:ecrire", "dispenser",
    "paiement:initier",
    "rendezvous:gerer", "reference:gerer", "laboratoire:ecrire",
  ],
  pharmacien: ["patient:lire", "prescription:lire", "dispenser", "stock:gerer"],
  agent_financier: ["patient:lire", "paiement:initier", "paiement:lire"],
  superviseur: [
    "patient:lire", "consultation:lire", "prescription:lire",
    "paiement:lire", "reference:gerer", "audit:lire",
  ],
  agent_saisie: ["patient:lire", "patient:ecrire"],
};

/** Code backend d'un rôle front (nomenclatures désormais ALIGNÉES). */
export function backendCode(role: StaffRole): BackendRoleCode {
  return role.toLowerCase() as BackendRoleCode;
}

/** LE test utilisé par les gardes : le rôle porte-t-il la permission ? */
export function roleHasPermission(role: StaffRole, permission: PermissionCode): boolean {
  return (RBAC_MATRIX[backendCode(role)] ?? []).includes(permission);
}

/**
 * Vues autorisées — DÉDUITES des permissions (plus une table ad hoc
 * divergente). Une vue existe s'il existe AU MOINS une action permise.
 */
export function viewsForRole(role: StaffRole): ViewId[] {
  const p = (c: PermissionCode) => roleHasPermission(role, c);
  const views: ViewId[] = ["dashboard", "sync"];
  if (p("patient:lire")) views.push("patients");
  if (p("consultation:ecrire") || p("consultation:lire")) views.push("consultation");
  if (p("prescription:lire")) views.push("prescriptions");
  if (p("paiement:lire") || p("paiement:initier")) views.push("payments");
  // I5 : la caisse (ticket d'accès) — paiement:initier pour encaisser/
  // exonérer, paiement:lire pour la file (superviseur).
  if (p("paiement:initier") || p("paiement:lire")) views.push("caisse");
  if (p("rendezvous:gerer")) views.push("appointments");
  if (p("stock:gerer")) views.push("stock");
  if (p("reference:gerer")) views.push("references");
  if (p("audit:lire")) {
    views.push("statistics");
    views.push("audit");
    views.push("backoffice");
  }
  if (p("admin:gerer")) views.push("backoffice");
  return Array.from(new Set(views));
}

/** Héritage de la nomenclature historique (ROLE_VIEWS déplacé ici). */
export const ROLE_VIEWS: Record<StaffRole, ViewId[]> = {
  AGENT_SAISIE: viewsForRole("AGENT_SAISIE"),
  INFIRMIER: viewsForRole("INFIRMIER"),
  MEDECIN: viewsForRole("MEDECIN"),
  PHARMACIEN: viewsForRole("PHARMACIEN"),
  AGENT_FINANCIER: viewsForRole("AGENT_FINANCIER"),
  SUPERVISEUR: viewsForRole("SUPERVISEUR"),
  ADMIN: viewsForRole("ADMIN"),
};

/** Pour l'onglet back-office : ce qui est APPLIQUÉ depuis V14. */
export const RBAC_ENFORCED_TODAY: { title: string; detail: string }[] = [
  {
    title: "Intercepteur par permission (back, V14)",
    detail:
      "FiltrePermissions exige la permission de chaque route /api/v1/** — refus 403 problem+json + entrée d'audit PERMISSION_DENIED.",
  },
  {
    title: "Authentification interne (V14)",
    detail:
      "POST /api/v1/auth/login (BCrypt + MFA admin) et OTP patient → jetons HS256 ; securite.jwt.actif est VRAI par défaut.",
  },
  {
    title: "Périmètre patient côté API",
    detail:
      "Un jeton patient n'accède qu'à SES données (dossier, consultations, ordonnances, RDV) — méthode-aware, propriété vérifiée.",
  },
  {
    title: "Gardes front par permission",
    detail:
      "Toutes les vues masquent les actions hors périmètre (usePermission — consultation, création MPI, caisse, initiation, réconciliation, dispensation, stock, RDV, références, labo, décès) et les routes mock vérifient le jeton (401/403 honnêtes). Sans session : aucune action offerte.",
  },
];

/** Ce qui reste à câbler (honnêteté de rigueur). */
export const RBAC_GAPS: { title: string; detail: string }[] = [
  {
    title: "Cloisonnement par structure (P0.6)",
    detail:
      "Les permissions sont par rôle ; le filtrage par structure d'appartenance (GUC prêtes côté RLS) reste à câbler sur les listes.",
  },
  {
    title: "Vérification croisée matrice/RLS en CI",
    detail: "BackofficeIT verrouille table↔domaine ; un test croisé front/back reste à écrire.",
  },
];
