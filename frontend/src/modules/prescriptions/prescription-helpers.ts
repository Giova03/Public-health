"use client";

/**
 * Ordonnances (E3) — helpers purs.
 * Dérivations d'affichage : avancement de la dispensation cumulée,
 * détection des contre-entrées existantes, libellés de statut.
 */

import { normalize } from "@/lib/demo/matcher";
import type { Prescription } from "@/lib/types";

export const STATUS_LABELS: Record<Prescription["status"], string> = {
  ACTIVE: "En cours",
  COMPLETED: "Soldée",
  CANCELLED: "Annulée",
};

export const STATUS_BADGE_CLASS: Record<Prescription["status"], string> = {
  ACTIVE:
    "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
  COMPLETED:
    "border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  CANCELLED: "border-destructive/30 bg-destructive/10 text-destructive",
};

/** Avancement global : part des quantités prescrites déjà dispensées (net). */
export function advancement(prescription: Prescription): {
  done: number;
  total: number;
  ratio: number; // 0..1
} {
  const net = netOf(prescription);
  let done = 0;
  let total = 0;
  for (const item of prescription.items) {
    total += item.quantity;
    done += Math.min(item.quantity, net.get(item.drug) ?? 0);
  }
  return { done, total, ratio: total > 0 ? done / total : 0 };
}

/** Cumul net dispensé par médicament (contre-entrées déduites). */
export function netOf(prescription: Prescription): Map<string, number> {
  const net = new Map<string, number>();
  for (const d of prescription.dispenses) {
    const sign = d.counterEntryOf ? -1 : 1;
    for (const line of d.lines) {
      net.set(line.drug, (net.get(line.drug) ?? 0) + sign * line.quantity);
    }
  }
  return net;
}

/** Reste à dispenser pour une ligne prescrite. */
export function remainingOf(
  prescription: Prescription,
  drug: string,
  prescribed: number,
): number {
  const net = netOf(prescription);
  return Math.max(0, prescribed - (net.get(drug) ?? 0));
}

/** Identifiants des dispensations déjà annulées par une contre-entrée. */
export function counteredIds(prescription: Prescription): Set<string> {
  return new Set(
    prescription.dispenses
      .filter((d) => d.counterEntryOf)
      .map((d) => d.counterEntryOf as string),
  );
}

/** Recherche : patient, diagnostic, prescripteur — diacritiques ignorées. */
export function searchPrescriptions(
  prescriptions: Prescription[],
  rawQuery: string,
): Prescription[] {
  const q = normalize(rawQuery);
  if (q.length < 2) return prescriptions;
  return prescriptions.filter((p) => {
    const name = p.patientName
      ? `${p.patientName.family} ${p.patientName.given}`
      : "";
    const hay = normalize(
      `${name} ${p.diagnosis} ${p.prescriber} ${p.facility}`,
    );
    return hay.includes(q);
  });
}
