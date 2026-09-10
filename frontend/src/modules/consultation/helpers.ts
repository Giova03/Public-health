"use client";

/**
 * Consultation (E3) — helpers purs.
 *
 * Aucune donnée métier ici : seulement de la dérivation d'état
 * (fréquence → doses/jour → quantité suggérée, recherche patient
 * insensible aux diacritiques, âge, initiales). Tout est réutilisable
 * côté rendu sans crainte de mutation.
 */

import { normalize } from "@/lib/demo/matcher";
import { DRUGS } from "@/lib/demo/reference";
import type { Patient, PatientName } from "@/lib/types";

/* ------------------------------------------------------------------ */
/* Brouillon d'ordonnance                                             */
/* ------------------------------------------------------------------ */

export interface DraftLine {
  /** DCI (clé du référentiel DRUGS). */
  drug: string;
  dosage: string;
  frequency: string;
  durationDays: number;
  quantity: number;
  /** L'utilisateur a écrasé la quantité suggérée. */
  quantityTouched: boolean;
  /** L'utilisateur a écrasé le dosage prérempli depuis la forme. */
  dosageTouched: boolean;
}

export const FREQUENCIES = [
  "1×/jour",
  "2×/jour",
  "3×/jour",
  "après chaque selle",
  "à jeun 1×/matin",
] as const;

export const DEFAULT_FREQUENCY: string = FREQUENCIES[1]; // "2×/jour"
export const DEFAULT_DURATION_DAYS = 5;

/** Doses par jour extraites du libellé ("2×/jour" → 2, sinon 1). */
export function dosesPerDay(frequency: string): number {
  const m = /(\d+)\s*[×x]/i.exec(frequency);
  return m ? Number(m[1]) : 1;
}

/** Quantité suggérée = doses/jour × durée (éditable ensuite). */
export function suggestedQuantity(
  frequency: string,
  durationDays: number,
): number {
  return dosesPerDay(frequency) * Math.max(0, Math.floor(durationDays));
}

/** Dosage prérempli depuis la forme galénique (« comprimé 20/120 mg » → « 20/120 mg »). */
export function dosageFromForm(form: string): string {
  const stripped = form
    .replace(
      /^(comprimé|gélule|capsule|sachet|poche|sirop|solution|suspension)\s*/i,
      "",
    )
    .trim();
  return stripped.length > 0 ? stripped : form;
}

export type ReferenceDrug = (typeof DRUGS)[number];

export function drugByDci(dci: string): ReferenceDrug | undefined {
  return DRUGS.find((d) => d.dci === dci);
}

/** Nouvelle ligne vierge (médicament non choisi, valeurs par défaut). */
export function makeLine(): DraftLine {
  return {
    drug: "",
    dosage: "",
    frequency: DEFAULT_FREQUENCY,
    durationDays: DEFAULT_DURATION_DAYS,
    quantity: suggestedQuantity(DEFAULT_FREQUENCY, DEFAULT_DURATION_DAYS),
    quantityTouched: false,
    dosageTouched: false,
  };
}

/* ------------------------------------------------------------------ */
/* Examen clinique                                                    */
/* ------------------------------------------------------------------ */

export const OTHER_DIAGNOSIS = "__autre__";

export interface ExamDraft {
  motif: string;
  /** Valeur du Select : diagnostic du référentiel ou OTHER_DIAGNOSIS. */
  diagnosisChoice: string;
  /** Saisie libre quand diagnosisChoice === OTHER_DIAGNOSIS. */
  diagnosisOther: string;
  bloodPressure: string;
  temperature: string;
  weight: string;
  notes: string;
}

export const EMPTY_EXAM: ExamDraft = {
  motif: "",
  diagnosisChoice: "",
  diagnosisOther: "",
  bloodPressure: "",
  temperature: "",
  weight: "",
  notes: "",
};

/** Diagnostic résolu (référentiel ou saisie libre) — vide si non défini. */
export function resolvedDiagnosis(exam: ExamDraft): string {
  return exam.diagnosisChoice === OTHER_DIAGNOSIS
    ? exam.diagnosisOther.trim()
    : exam.diagnosisChoice;
}

/** Décimale à la française pour les récapitulatifs (« 37.5 » → « 37,5 »). */
export function frDecimal(raw: string): string {
  return raw.trim().replace(".", ",");
}

/* ------------------------------------------------------------------ */
/* Patient                                                            */
/* ------------------------------------------------------------------ */

/** Âge en années révolues (null si date invalide). */
export function ageOf(birthDate: string): number | null {
  const b = new Date(birthDate);
  if (Number.isNaN(b.getTime())) return null;
  const now = new Date();
  let age = now.getFullYear() - b.getFullYear();
  const m = now.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < b.getDate())) age -= 1;
  return age >= 0 && age < 130 ? age : null;
}

export function displayName(name: PatientName): string {
  return `${name.family} ${name.given}`.trim();
}

export function initialsOf(name: PatientName): string {
  const initials = `${name.given.charAt(0)}${name.family.charAt(0)}`;
  return initials.toUpperCase();
}

/**
 * Mini-recherche MPI : patronyme / prénom / phReference,
 * insensible à la casse et aux diacritiques (« ouedraogo »
 * trouve « OUÉDRAOGO »). Chaque mot du requête doit matcher.
 */
export function searchPatients(
  patients: Patient[],
  rawQuery: string,
  limit = 8,
): Patient[] {
  const tokens = normalize(rawQuery).split(" ").filter(Boolean);
  if (tokens.length === 0) return [];
  return patients
    .filter((p) => p.active)
    .filter((p) => {
      const haystack = normalize(
        `${p.name.family} ${p.name.given} ${p.phReference}`,
      );
      return tokens.every((t) => haystack.includes(t));
    })
    .sort((a, b) => a.name.family.localeCompare(b.name.family))
    .slice(0, limit);
}
