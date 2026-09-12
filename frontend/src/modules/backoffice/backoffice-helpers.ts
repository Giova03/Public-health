"use client";

/**
 * Module Back-office (E6) — helpers purs : badges de rôle par famille
 * (soins = teal, finance = ambre, gouvernance = neutre subtil — pas de
 * violet, par choix design), indicateurs dérivés du miroir local et
 * utilitaires d'affichage.
 */

import { formatDistanceToNow } from "date-fns";
import { fr } from "date-fns/locale";
import type {
  Patient,
  PaymentRecord,
  Prescription,
  StaffRole,
  StaffUser,
} from "@/lib/types";
import { STAFF_ROLE_LABELS } from "@/lib/demo/reference";

/* --------------------------- Badges de rôle ---------------------------- */

/**
 * Couleur par famille de rôle — cohérente avec les tokens de l'app :
 *  - soins (MPI, infirmiers, médecins, pharmaciens) : teal clinique ;
 *  - finance (caissiers) : ambre, distinct de l'état nominal comme de
 *    l'erreur ;
 *  - gouvernance : SUPERVISEUR en neutre, ADMIN en teinte destructive
 *    subtile (responsabilité élevée — violet proscrit).
 */
export const ROLE_BADGE_CLASS: Record<StaffRole, string> = {
  AGENT_SAISIE: "border-primary/30 bg-primary/10 text-primary",
  INFIRMIER: "border-primary/30 bg-primary/10 text-primary",
  MEDECIN: "border-primary/30 bg-primary/10 text-primary",
  PHARMACIEN: "border-primary/30 bg-primary/10 text-primary",
  AGENT_FINANCIER:
    "border-amber-600/30 bg-amber-500/10 text-amber-600 dark:border-amber-400/30 dark:text-amber-400",
  SUPERVISEUR: "border-border bg-muted/60 text-muted-foreground",
  ADMIN: "border-destructive/30 bg-destructive/5 text-destructive",
};

export const MFA_ENABLED_BADGE_CLASS =
  "border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400";

export const MFA_ABSENT_BADGE_CLASS = "border-border text-muted-foreground";

/* ------------------------------ Filtre rôles --------------------------- */

export type RoleFilterValue = "ALL" | StaffRole;

export const ROLE_FILTER_OPTIONS: { value: RoleFilterValue; label: string }[] = [
  { value: "ALL", label: "Tous les rôles" },
  ...(Object.keys(STAFF_ROLE_LABELS) as StaffRole[]).map((role) => ({
    value: role,
    label: STAFF_ROLE_LABELS[role],
  })),
];

/* ------------------------------ Indicateurs ---------------------------- */

export interface GovernanceStats {
  activePatients: number;
  activePrescriptions: number;
  pendingPayments: number;
  failedPayments: number;
}

/**
 * Indicateurs de supervision — dérivés du miroir local (offline-friendly,
 * mêmes définitions que la route GET /api/v1/backoffice).
 */
export function computeGovernanceStats(mirror: {
  patients: Patient[];
  prescriptions: Prescription[];
  payments: PaymentRecord[];
}): GovernanceStats {
  return {
    activePatients: mirror.patients.filter((p) => p.active).length,
    activePrescriptions: mirror.prescriptions.filter(
      (r) => r.status === "ACTIVE",
    ).length,
    pendingPayments: mirror.payments.filter(
      (m) => m.state === "PENDING" || m.state === "INITIATED",
    ).length,
    failedPayments: mirror.payments.filter((m) => m.state === "FAILED").length,
  };
}

/* ----------------------------- Utilitaires ----------------------------- */

/** « il y a 3 jours » — null si la date est invalide. */
export function relativeFromNow(iso: string, ref: Date): string | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime()) || Number.isNaN(ref.getTime())) return null;
  try {
    return formatDistanceToNow(date, { addSuffix: true, locale: fr });
  } catch {
    return null;
  }
}

/** Initiales « AB » pour l'avatar d'un compte. */
export function initialsOf(fullName: string): string {
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "—";
  const first = parts[0].charAt(0);
  const last = parts.length > 1 ? parts[parts.length - 1].charAt(0) : "";
  return `${first}${last}`.toUpperCase();
}

/** Comptes ADMIN sans MFA — seraient suspendus (ADR sécurité). */
export function findAdminsWithoutMfa(users: StaffUser[]): StaffUser[] {
  return users.filter((u) => u.role === "ADMIN" && !u.mfaEnabled);
}
