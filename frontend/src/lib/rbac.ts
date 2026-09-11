/**
 * PUBLIC HEALTH — matrice RBAC de référence (miroir front du backend).
 *
 * Source de vérité : `RolesPermissions.java` (domaine pur, épique E6) et
 * la migration V12 qui sème `administration.role_permission` à l'identique
 * — l'égalité table/domaine est verrouillée par BackofficeIT. Ce fichier
 * est le miroir côté front : si la matrice backend évolue, ce fichier doit
 * suivre (le jour du re-câblage sur l'API réelle, il sera servi par
 * `GET /api/v1/admin/me/permissions`).
 *
 * L'état d'application (« déclarée » vs « appliquée ») est documenté ici
 * aussi honnêtement que dans le rapport d'analyse RBAC : la matrice est
 * déclarée et exposée, les gardes réelles aujourd'hui sont (1) la route
 * /api/v1/admin/** gardée par ROLE_ADMIN, (2) la RLS par propriété
 * (V10) et (3) l'append-only. L'interception fine par permission sur
 * chaque endpoint est le chantier E6 restant.
 */

export type BackendRoleCode =
  | "admin"
  | "medecin"
  | "infirmier"
  | "pharmacien"
  | "agent_financier"
  | "superviseur";

export type PermissionCode =
  | "patient:lire"
  | "patient:ecrire"
  | "prescription:lire"
  | "prescription:ecrire"
  | "dispenser"
  | "paiement:initier"
  | "paiement:lire"
  | "paiement:reconcilier"
  | "audit:lire"
  | "admin:gerer";

export interface BackendRole {
  code: BackendRoleCode;
  label: string;
  mission: string;
  /** Rôle démo front équivalent (nomenclature de simulation). */
  demoStaffRole:
    | "ADMIN"
    | "MEDECIN"
    | "INFIRMIER"
    | "PHARMACIEN"
    | "CAISSIER"
    | "SUPERVISEUR";
}

/** Les six rôles du CHECK V12 — la nomenclature de référence. */
export const BACKEND_ROLES: BackendRole[] = [
  {
    code: "admin",
    label: "Administrateur",
    mission: "Back-office total : structures, comptes, rôles, réconciliation.",
    demoStaffRole: "ADMIN",
  },
  {
    code: "medecin",
    label: "Médecin",
    mission: "Dossier patient et prescription.",
    demoStaffRole: "MEDECIN",
  },
  {
    code: "infirmier",
    label: "Infirmier / Infirmière",
    mission: "Admission MPI et frais d'accès au comptoir (CSPS).",
    demoStaffRole: "INFIRMIER",
  },
  {
    code: "pharmacien",
    label: "Pharmacien / Pharmacienne",
    mission: "Dispensation au comptoir, contre-entrées.",
    demoStaffRole: "PHARMACIEN",
  },
  {
    code: "agent_financier",
    label: "Agent financier (caissier)",
    mission: "Paiements et facturation.",
    demoStaffRole: "CAISSIER",
  },
  {
    code: "superviseur",
    label: "Superviseur / Superviseure",
    mission: "Supervision en lecture : paiements, audit.",
    demoStaffRole: "SUPERVISEUR",
  },
];

export interface PermissionInfo {
  code: PermissionCode;
  label: string;
  scope: string;
}

/** Les dix permissions de la nomenclature close. */
export const RBAC_PERMISSIONS: PermissionInfo[] = [
  {
    code: "patient:lire",
    label: "Lire le MPI",
    scope: "Recherche et lecture du dossier patient (miroir national).",
  },
  {
    code: "patient:ecrire",
    label: "Écrire le MPI",
    scope: "Création du dossier, création forcée après 409 (motif tracé).",
  },
  {
    code: "prescription:lire",
    label: "Lire les ordonnances",
    scope: "Dossier pharmacologique (lecture).",
  },
  {
    code: "prescription:ecrire",
    label: "Prescrire",
    scope: "Émission d'une ordonnance (append-only V8).",
  },
  {
    code: "dispenser",
    label: "Dispenser",
    scope: "Dispensation au comptoir, cumul contrôlé, contre-entrée.",
  },
  {
    code: "paiement:initier",
    label: "Initier un paiement",
    scope: "Initiation FedaPay, frais d'accès (idempotent).",
  },
  {
    code: "paiement:lire",
    label: "Suivre les paiements",
    scope: "Paiements et factures (lecture).",
  },
  {
    code: "paiement:reconcilier",
    label: "Réconcilier",
    scope: "Lancer le run de réconciliation qui fait foi.",
  },
  {
    code: "audit:lire",
    label: "Lire l'audit",
    scope: "Journal d'audit et brèches d'accès d'urgence.",
  },
  {
    code: "admin:gerer",
    label: "Gérer le back-office",
    scope: "Structures, utilisateurs, rôles, MFA, suspensions.",
  },
];

/**
 * La matrice — copie EXACTE de RolesPermissions.construireMatrice()
 * (l'ordre d'insertion suit le semis V12). Aucune permission orpheline,
 * aucun rôle sans patient:lire.
 */
export const RBAC_MATRIX: Record<BackendRoleCode, PermissionCode[]> = {
  admin: [
    "patient:lire",
    "patient:ecrire",
    "prescription:lire",
    "prescription:ecrire",
    "dispenser",
    "paiement:initier",
    "paiement:lire",
    "paiement:reconcilier",
    "audit:lire",
    "admin:gerer",
  ],
  medecin: ["patient:lire", "patient:ecrire", "prescription:lire", "prescription:ecrire"],
  infirmier: ["patient:lire", "patient:ecrire", "paiement:initier"],
  pharmacien: ["patient:lire", "prescription:lire", "dispenser"],
  agent_financier: ["patient:lire", "paiement:initier", "paiement:lire"],
  superviseur: ["patient:lire", "prescription:lire", "paiement:lire", "audit:lire"],
};

export function roleHasPermission(
  role: BackendRoleCode,
  permission: PermissionCode,
): boolean {
  return RBAC_MATRIX[role].includes(permission);
}

/** Ce qui est réellement appliqué aujourd'hui (analyse RBAC, chap. 9). */
export const RBAC_ENFORCED_TODAY: { title: string; detail: string }[] = [
  {
    title: "Route /api/v1/admin/** gardée par rôle",
    detail:
      "Spring Security exige ROLE_ADMIN sur tout le back-office (403 problem+json sinon, refus journalisé) — la seule garde HTTP par rôle du produit.",
  },
  {
    title: "RLS par propriété (V10)",
    detail:
      "Chaque table attribuable ne laisse lire/écrire une ligne qu'à son propriétaire ou à l'admin (fail-closed, testé par RlsAppRwIT) : paiements à l'initiateur, ordonnances au prescripteur, dispensations au dispensateur.",
  },
  {
    title: "Append-only + machines à états",
    detail:
      "Aucun octroi DELETE pour le rôle applicatif, gardes SQL V3/V8/V12, paiement à 8 états forward-only, suspension motivée : le passé ne se réécrit pas.",
  },
];

/** Les trois écarts découverts par l'analyse (à corriger — P0.5). */
export const RBAC_GAPS: { title: string; detail: string }[] = [
  {
    title: "Écart matrice / RLS sur les lectures croisées",
    detail:
      "La matrice accorde prescription:lire au pharmacien et paiement:lire à l'agent financier et au superviseur ; les policies V10 ne laissent lire qu'au propriétaire ou à l'admin : sous app_rw, le comptoir ne peut pas ouvrir l'ordonnance du médecin. Arbitrage requis.",
  },
  {
    title: "Interception par permission non câblée",
    detail:
      "La matrice est déclarée, persistée (V12) et exposée (/admin/me/permissions), mais aucun endpoint hors /admin/** ne la vérifie : seule /admin/** est gardée par rôle, la RLS borne par propriété. Câbler l'intercepteur est le chantier E6 restant.",
  },
  {
    title: "Périmètre structure inerte",
    detail:
      "Les policies facility (V5) et app.current_facility_ids() existent, mais le filtre ne pose jamais app.facility_ids : le scope clinique effectif est le praticien ou l'admin, jamais la structure.",
  },
];
