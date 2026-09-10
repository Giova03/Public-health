"use client";

/**
 * Module Paiements (E4) — helpers purs : états/canaux, filtres, KPI.
 * Machine à 8 états forward-only — la table PAYMENT_TRANSITIONS fait foi.
 */

import { Banknote, CreditCard, Smartphone } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { PAYMENT_TRANSITIONS } from "@/lib/types";
import type {
  PatientName,
  PaymentChannel,
  PaymentRecord,
  PaymentState,
} from "@/lib/types";

/* ----------------------------- Libellés états ------------------------- */

export const STATE_LABELS: Record<PaymentState, string> = {
  INITIATED: "Initié",
  PENDING: "En cours",
  AUTHORIZED: "Autorisé",
  SUCCEEDED: "Reçu",
  RECONCILED: "Réconcilié",
  FAILED: "Échec",
  CANCELLED: "Annulé",
  REFUNDED: "Remboursé",
};

/** Badge d'état (tokens sémantiques — muted / primary / emerald / destructive). */
export const STATE_BADGE_CLASS: Record<PaymentState, string> = {
  INITIATED:
    "border-amber-600/30 bg-amber-500/10 text-amber-600 dark:border-amber-400/30 dark:text-amber-400",
  PENDING:
    "border-amber-600/30 bg-amber-500/10 text-amber-600 dark:border-amber-400/30 dark:text-amber-400",
  AUTHORIZED: "border-primary/30 bg-primary/10 text-primary",
  SUCCEEDED:
    "border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400",
  RECONCILED:
    "border-transparent bg-emerald-600 text-white dark:bg-emerald-400 dark:text-emerald-950",
  FAILED: "border-destructive/30 bg-destructive/10 text-destructive",
  CANCELLED: "border-border bg-muted text-muted-foreground",
  REFUNDED:
    "border-dashed border-border bg-muted/60 text-muted-foreground opacity-80",
};

/* ------------------------------- Canaux ------------------------------- */

export interface ChannelConfig {
  label: string;
  short: string;
  hint: string;
  icon: LucideIcon;
}

export const CHANNEL_CONFIG: Record<PaymentChannel, ChannelConfig> = {
  MOBILE_MONEY: {
    label: "Mobile money (Orange/OBI/Moov)",
    short: "Mobile money",
    hint: "Orange Money, OBI, Moov Money",
    icon: Smartphone,
  },
  ESPECES: {
    label: "Espèces (caisse)",
    short: "Espèces",
    hint: "Reçu de caisse immédiat",
    icon: Banknote,
  },
  CARTE: {
    label: "Carte bancaire",
    short: "Carte",
    hint: "TPE / paiement à distance",
    icon: CreditCard,
  },
};

/* --------------------- Actions contextuelles (fiche) ------------------ */

/** Cibles possibles depuis un état — INITIATED n'est jamais une cible. */
export type PaymentTarget = Exclude<PaymentState, "INITIATED">;

export interface ActionConfig {
  label: string;
  description: string;
  /** États exceptionnels : confirmation AlertDialog obligatoire. */
  danger: boolean;
}

export const ACTION_CONFIG: Record<PaymentTarget, ActionConfig> = {
  PENDING: {
    label: "Confirmer l'envoi à l'opérateur",
    description: "Le paiement passe « Initié » → « En cours » chez l'opérateur.",
    danger: false,
  },
  AUTHORIZED: {
    label: "Autorisation reçue",
    description: "Webhook opérateur : la transaction est autorisée.",
    danger: false,
  },
  SUCCEEDED: {
    label: "Confirmer l'encaissement",
    description: "Webhook opérateur : les fonds sont capturés (reçu émis).",
    danger: false,
  },
  RECONCILED: {
    label: "Réconcilier",
    description: "Job nocturne (23 h) : rapprochement du journal opérateur, fait-foi.",
    danger: false,
  },
  FAILED: {
    label: "Déclarer un échec",
    description: "Webhook d'échec — irréversible.",
    danger: true,
  },
  CANCELLED: {
    label: "Annuler",
    description: "Annulation définitive du paiement — irréversible.",
    danger: true,
  },
  REFUNDED: {
    label: "Rembourser",
    description: "Remboursement intégral du patient — irréversible.",
    danger: true,
  },
};

/** Cibles autorisées (table des transitions, forward-only). */
export function targetsOf(state: PaymentState): PaymentTarget[] {
  return PAYMENT_TRANSITIONS[state] as PaymentTarget[];
}

/* ------------------------------- Filtres ------------------------------ */

export type PaymentFilter =
  | "ALL"
  | "IN_PROGRESS"
  | "SUCCEEDED"
  | "RECONCILED"
  | "REVIEW";

export const PAYMENT_FILTERS: { value: PaymentFilter; label: string }[] = [
  { value: "ALL", label: "Toutes" },
  { value: "IN_PROGRESS", label: "En cours" },
  { value: "SUCCEEDED", label: "Reçus" },
  { value: "RECONCILED", label: "Réconciliés" },
  { value: "REVIEW", label: "À revoir" },
];

export function applyPaymentsFilter(
  payments: PaymentRecord[],
  filter: PaymentFilter,
): PaymentRecord[] {
  switch (filter) {
    case "IN_PROGRESS":
      return payments.filter((p) =>
        p.state === "INITIATED" || p.state === "PENDING" || p.state === "AUTHORIZED",
      );
    case "SUCCEEDED":
      return payments.filter((p) => p.state === "SUCCEEDED");
    case "RECONCILED":
      return payments.filter((p) => p.state === "RECONCILED");
    case "REVIEW":
      return payments.filter((p) => p.state === "FAILED" || p.state === "REFUNDED");
    case "ALL":
    default:
      return payments;
  }
}

/** Normalisation sans accents pour la recherche (noms burkinabè). */
function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

export function searchPayments(
  payments: PaymentRecord[],
  query: string,
): PaymentRecord[] {
  const needle = normalize(query.trim());
  if (!needle) return payments;
  return payments.filter((p) => {
    const name = p.patientName
      ? `${p.patientName.family} ${p.patientName.given}`
      : "";
    return (
      normalize(name).includes(needle) || normalize(p.purpose).includes(needle)
    );
  });
}

/** Tri createdAt décroissant (le plus récent d'abord). */
export function sortPaymentsDesc(payments: PaymentRecord[]): PaymentRecord[] {
  return [...payments].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

/* --------------------------------- KPI -------------------------------- */

export interface PaymentKpis {
  /** Σ SUCCEEDED + RECONCILED créés aujourd'hui (F CFA). */
  encaisseDuJour: number;
  /** Σ INITIATED + PENDING (F CFA). */
  enAttente: number;
  /** Nombre de paiements en attente (INITIATED + PENDING). */
  enAttenteCount: number;
  /** Nombre de paiements FAILED. */
  echecs: number;
}

function isSameLocalDay(iso: string, ref: Date): boolean {
  const d = new Date(iso);
  return (
    d.getFullYear() === ref.getFullYear() &&
    d.getMonth() === ref.getMonth() &&
    d.getDate() === ref.getDate()
  );
}

export function computePaymentKpis(
  payments: PaymentRecord[],
  ref: Date | null,
): PaymentKpis {
  if (!ref) {
    return { encaisseDuJour: 0, enAttente: 0, enAttenteCount: 0, echecs: 0 };
  }
  let encaisseDuJour = 0;
  let enAttente = 0;
  let enAttenteCount = 0;
  let echecs = 0;
  for (const p of payments) {
    if (
      (p.state === "SUCCEEDED" || p.state === "RECONCILED") &&
      isSameLocalDay(p.createdAt, ref)
    ) {
      encaisseDuJour += p.amountXof;
    }
    if (p.state === "INITIATED" || p.state === "PENDING") {
      enAttente += p.amountXof;
      enAttenteCount += 1;
    }
    if (p.state === "FAILED") echecs += 1;
  }
  return { encaisseDuJour, enAttente, enAttenteCount, echecs };
}

/* ------------------------------ Divers -------------------------------- */

/** Nom d'affichage « FAMILY Given » (fallback patient du miroir). */
export function patientDisplayName(
  name: PatientName | undefined,
  patients: { id: string; name: PatientName }[],
  patientId: string,
): string {
  if (name) return `${name.family} ${name.given}`;
  const mirror = patients.find((p) => p.id === patientId);
  return mirror ? `${mirror.name.family} ${mirror.name.given}` : "Patient inconnu";
}

/** Âge (années révolues) — utilisé dans la fiche paiement. */
export function ageFromBirthDate(birthDate: string, ref: Date): number {
  const birth = new Date(birthDate);
  let age = ref.getFullYear() - birth.getFullYear();
  const beforeBirthday =
    ref.getMonth() < birth.getMonth() ||
    (ref.getMonth() === birth.getMonth() && ref.getDate() < birth.getDate());
  if (beforeBirthday) age -= 1;
  return Math.max(age, 0);
}
