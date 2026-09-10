/**
 * PUBLIC HEALTH — données de référence partagées (client + serveur).
 * Données publiques non sensibles : structures sanitaires du Burkina Faso,
 * médicaments de la liste nationale (DCI), tarifs indicatifs, canaux.
 * Servent aux formulaires et à la simulation ; aucune donnée PHI réelle.
 */

import type { FacilityKind, HealthFacility, PaymentChannel, StaffRole } from "@/lib/types";

export const FACILITIES: HealthFacility[] = [
  { id: "f-csps-12", name: "CSPS Ouaga 12", kind: "CSPS", region: "Centre", staffCount: 9, online: true },
  { id: "f-csps-tanghin", name: "CSPS Tanghin", kind: "CSPS", region: "Centre", staffCount: 7, online: true },
  { id: "f-cma-kossodo", name: "CMA Kossodo", kind: "CMA", region: "Centre", staffCount: 18, online: true },
  { id: "f-chr-ouahigouya", name: "CHR Ouahigouya", kind: "CHR", region: "Nord", staffCount: 42, online: false },
  { id: "f-chu-yo", name: "CHU Yalgado Ouédraogo", kind: "CHU", region: "Centre", staffCount: 210, online: true },
  { id: "f-drs-centre", name: "DRS Centre", kind: "DRS", region: "Centre", staffCount: 12, online: true },
];

export const FACILITY_NAMES = FACILITIES.map((f) => f.name);

export const FACILITY_KIND_LABELS: Record<FacilityKind, string> = {
  CSPS: "Centre de santé et promotion sociale",
  CMA: "Centre médical avec antenne chirurgicale",
  CHR: "Centre hospitalier régional",
  CHU: "Centre hospitalier universitaire",
  DRS: "Direction régionale de la santé",
};

/** DCI courants de la liste nationale (démonstration). */
export const DRUGS = [
  { dci: "Artéméther/Luméfantrine", form: "comprimé 20/120 mg", unitPriceXof: 250, stock: 480 },
  { dci: "Amoxicilline", form: "gélule 500 mg", unitPriceXof: 75, stock: 1250 },
  { dci: "Paracétamol", form: "comprimé 500 mg", unitPriceXof: 25, stock: 2400 },
  { dci: "Amlodipine", form: "comprimé 5 mg", unitPriceXof: 90, stock: 320 },
  { dci: "Metformine", form: "comprimé 850 mg", unitPriceXof: 60, stock: 540 },
  { dci: "SRO", form: "sachet", unitPriceXof: 50, stock: 800 },
  { dci: "Sulfate de zinc", form: "comprimé 20 mg", unitPriceXof: 40, stock: 610 },
  { dci: "Cotrimoxazole", form: "comprimé 480 mg", unitPriceXof: 55, stock: 720 },
  { dci: "Fer + acide folique", form: "comprimé", unitPriceXof: 30, stock: 900 },
  { dci: "Ringer Lactate", form: "poche 500 ml", unitPriceXof: 350, stock: 150 },
] as const;

export const DIAGNOSES = [
  "Paludisme simple",
  "Paludisme grave",
  "Infection respiratoire aiguë",
  "Diarrhée aiguë",
  "Hypertension artérielle",
  "Diabète type 2",
  "Anémie ferriprive",
  "Plaie simple",
];

/** Motifs de paiement et tarifs unitaires indicatifs (démonstration). */
export const PAYMENT_PURPOSES = [
  { code: "CONSULTATION", label: "Consultation générale", amountXof: 1000 },
  { code: "ORDONNANCE", label: "Ordonnance (dispensation)", amountXof: 3500 },
  { code: "LABO", label: "Examens de laboratoire", amountXof: 2000 },
  { code: "HOSPITALISATION", label: "Hospitalisation (jour)", amountXof: 12000 },
] as const;

export const PAYMENT_CHANNELS: { value: PaymentChannel; label: string }[] = [
  { value: "MOBILE_MONEY", label: "Mobile money (Orange/OBI/Moov)" },
  { value: "ESPECES", label: "Espèces (caisse)" },
  { value: "CARTE", label: "Carte bancaire" },
];

export const STAFF_ROLE_LABELS: Record<StaffRole, string> = {
  AGENT_SAISIE: "Agent de saisie (MPI)",
  INFIRMIER: "Infirmier / Infirmière",
  MEDECIN: "Médecin",
  PHARMACIEN: "Pharmacien",
  CAISSIER: "Caissier",
  SUPERVISEUR: "Superviseur régional",
  ADMIN: "Administrateur (MFA obligatoire)",
};

/** Régions du Burkina Faso (pour la fiche patient / structures). */
export const REGIONS = [
  "Centre",
  "Plateau-Central",
  "Nord",
  "Sahel",
  "Est",
  "Boucle du Mouhoun",
  "Hauts-Bassins",
  "Centre-Ouest",
  "Centre-Est",
  "Centre-Nord",
  "Cascades",
  "Sud-Ouest",
];

/** Utilisateur de démonstration connecté (avatar du header). */
export const CURRENT_USER = {
  fullName: "Aminata Sawadogo",
  role: "INFIRMIER" as StaffRole,
  facility: "CSPS Ouaga 12",
};
