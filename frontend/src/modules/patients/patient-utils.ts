"use client";

/**
 * Module Patients (E1) — utilitaires partagés du module.
 *
 * Âge, libellés, initiales, recherche insensible aux diacritiques
 * (« ouedraogo » doit trouver « OUÉDRAOGO ») et tris. Aucune donnée PHI
 * réelle : démonstration.
 */

import { differenceInMonths, differenceInYears, isValid, parseISO } from "date-fns";
import type { Patient, PatientName, PaymentState, PrescriptionStatus } from "@/lib/types";
import { normalize } from "@/lib/demo/matcher";

/* ------------------------------ Identité ------------------------------- */

/** Nom complet « FAMILLE Given » (patronyme en premier, usage administratif). */
export function fullName(name: PatientName): string {
  return `${name.family} ${name.given}`.trim();
}

/** Initiales pour l'avatar : première lettre du patronyme + du prénom. */
export function initials(name: PatientName): string {
  const f = name.family.trim().charAt(0);
  const g = name.given.trim().charAt(0);
  return `${f}${g}`.toUpperCase() || "?";
}

/** Teinte d'avatar déterministe (tokens sémantiques uniquement). */
export function avatarTone(seed: string): string {
  const tones = [
    "bg-primary/10 text-primary",
    "bg-accent text-accent-foreground",
    "bg-muted text-foreground",
  ];
  const sum = [...seed].reduce((acc, c) => acc + c.charCodeAt(0), 0);
  return tones[sum % tones.length] ?? tones[0]!;
}

export function genderLabel(gender: "M" | "F"): string {
  return gender === "M" ? "Homme" : "Femme";
}

/* -------------------------------- Âge ---------------------------------- */

/** Âge en années révolues, ou null si la date est invalide. */
export function ageInYears(birthDate: string): number | null {
  const date = parseISO(birthDate);
  if (!isValid(date)) return null;
  return differenceInYears(new Date(), date);
}

/** Libellé d'âge lisible : « 34 ans », « 8 mois », « 21 jours ». */
export function ageLabel(birthDate: string): string {
  const date = parseISO(birthDate);
  if (!isValid(date)) return "âge inconnu";
  const years = differenceInYears(new Date(), date);
  if (years >= 2) return `${years} ans`;
  if (years === 1) return "1 an";
  const months = differenceInMonths(new Date(), date);
  if (months >= 1) return `${months} mois`;
  const days = Math.max(0, Math.floor((Date.now() - date.getTime()) / 86_400_000));
  return `${days} jours`;
}

/* ------------------------------ Recherche ------------------------------ */

/** Le miroir ne montre que les dossiers actifs (les fusionnés partent en 410). */
export function activePatients(patients: Patient[]): Patient[] {
  return patients.filter((p) => p.active);
}

/**
 * Filtre miroir instantané : chaque mot de la requête doit apparaître dans
 * le nom, le téléphone ou la référence PH — après normalisation
 * diacritique/casse (le normaliseur est celui du moteur MPI, cohérent).
 */
export function filterMirrorPatients(patients: Patient[], rawQuery: string): Patient[] {
  const query = normalize(rawQuery);
  if (!query) return sortPatientsByUpdate(patients);
  const tokens = query.split(" ").filter(Boolean);
  return sortPatientsByUpdate(
    patients.filter((p) => {
      const hay = normalize(
        `${p.name.family} ${p.name.given} ${p.phone ?? ""} ${p.phReference}`,
      );
      return tokens.every((t) => hay.includes(t));
    }),
  );
}

/** Tri updatedAt décroissant (tie-breaker alphabétique patronyme). */
export function sortPatientsByUpdate(patients: Patient[]): Patient[] {
  return [...patients].sort(
    (a, b) =>
      b.updatedAt.localeCompare(a.updatedAt) ||
      a.name.family.localeCompare(b.name.family, "fr"),
  );
}

/* ------------------------ Libellés d'historique ------------------------ */

export const PRESCRIPTION_STATUS_LABELS: Record<
  PrescriptionStatus,
  { label: string; className: string }
> = {
  ACTIVE: {
    label: "Active",
    className:
      "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
  },
  COMPLETED: {
    label: "Terminée",
    className:
      "border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  },
  ENTERED_IN_ERROR: {
    label: "Erreur de saisie",
    className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300",
  },
  CANCELLED: {
    label: "Annulée",
    className: "border-destructive/30 bg-destructive/10 text-destructive",
  },
};

export const PAYMENT_STATE_LABELS: Record<
  PaymentState,
  { label: string; className: string }
> = {
  INITIATED: {
    label: "Initié",
    className: "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
  },
  PENDING: {
    label: "En attente",
    className: "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
  },
  AUTHORIZED: {
    label: "Autorisé",
    className: "border-primary/30 bg-primary/10 text-primary",
  },
  SUCCEEDED: {
    label: "Succès",
    className:
      "border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  },
  RECONCILED: {
    label: "Réconcilié",
    className: "border-transparent bg-primary text-primary-foreground",
  },
  FAILED: {
    label: "Échec",
    className: "border-destructive/30 bg-destructive/10 text-destructive",
  },
  CANCELLED: {
    label: "Annulé",
    className: "border-destructive/30 bg-destructive/10 text-destructive",
  },
  REFUNDED: {
    label: "Remboursé",
    className: "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
  },
};

/* ------------------------------- Doublons ------------------------------ */

/** Classe de couleur pour un verdict de rapprochement identitaire. */
export function verdictClasses(verdict: "BLOCKING" | "REVIEW"): {
  badge: string;
  bar: string;
} {
  return verdict === "BLOCKING"
    ? { badge: "", bar: "bg-destructive" }
    : {
        badge:
          "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
        bar: "bg-amber-500",
      };
}
