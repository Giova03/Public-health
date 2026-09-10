"use client";

/**
 * Module Tableau de bord — calculs purs dérivés du miroir (store).
 * Aucun appel réseau : tout est local (offline-first).
 */

import { netDispensed } from "@/lib/store";
import type {
  Patient,
  PaymentRecord,
  Prescription,
  SyncOperation,
} from "@/lib/types";

/* --------------------------------- KPI -------------------------------- */

export interface DashboardKpis {
  patientsCount: number;
  activePrescriptions: number;
  pendingPayments: number;
  encaisseDuJour: number;
}

function isSameLocalDay(iso: string, ref: Date): boolean {
  const d = new Date(iso);
  return (
    d.getFullYear() === ref.getFullYear() &&
    d.getMonth() === ref.getMonth() &&
    d.getDate() === ref.getDate()
  );
}

export function computeDashboardKpis(
  patients: Patient[],
  prescriptions: Prescription[],
  payments: PaymentRecord[],
  ref: Date | null,
): DashboardKpis {
  const encaisseDuJour = ref
    ? payments
        .filter(
          (p) =>
            (p.state === "SUCCEEDED" || p.state === "RECONCILED") &&
            isSameLocalDay(p.createdAt, ref),
        )
        .reduce((sum, p) => sum + p.amountXof, 0)
    : 0;

  return {
    patientsCount: patients.length,
    activePrescriptions: prescriptions.filter((r) => r.status === "ACTIVE")
      .length,
    pendingPayments: payments.filter(
      (p) =>
        p.state === "INITIATED" || p.state === "PENDING" || p.state === "AUTHORIZED",
    ).length,
    encaisseDuJour,
  };
}

/* ------------------------- Activité 7 derniers jours ------------------ */

export interface ActivityPoint {
  /** Clé locale YYYY-MM-DD (stable). */
  day: string;
  /** Libellé court FR : « lun. 09/06 ». */
  label: string;
  patients: number;
  paiements: number;
}

function localDayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(
    d.getDate(),
  ).padStart(2, "0")}`;
}

export function computeActivitySeries(
  patients: Patient[],
  payments: PaymentRecord[],
  ref: Date | null,
): ActivityPoint[] {
  if (!ref) return [];

  const days: Date[] = [];
  for (let i = 6; i >= 0; i -= 1) {
    const d = new Date(ref);
    d.setDate(d.getDate() - i);
    days.push(d);
  }

  const patientsByDay = new Map<string, number>();
  for (const p of patients) {
    const key = localDayKey(new Date(p.createdAt));
    patientsByDay.set(key, (patientsByDay.get(key) ?? 0) + 1);
  }
  const paymentsByDay = new Map<string, number>();
  for (const p of payments) {
    const key = localDayKey(new Date(p.createdAt));
    paymentsByDay.set(key, (paymentsByDay.get(key) ?? 0) + 1);
  }

  return days.map((d) => ({
    day: localDayKey(d),
    label: d.toLocaleDateString("fr-FR", {
      weekday: "short",
      day: "2-digit",
      month: "2-digit",
    }),
    patients: patientsByDay.get(localDayKey(d)) ?? 0,
    paiements: paymentsByDay.get(localDayKey(d)) ?? 0,
  }));
}

/* ------------------------------- Alertes ------------------------------ */

export function computeFailedPayments(
  payments: PaymentRecord[],
): PaymentRecord[] {
  return payments
    .filter((p) => p.state === "FAILED")
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}

export function computeFailedOps(
  outbox: SyncOperation[],
): SyncOperation[] {
  return outbox.filter((op) => op.status === "failed");
}

export interface PrescriptionToDispense {
  prescription: Prescription;
  /** Total d'unités restant à dispenser toutes lignes confondues. */
  remaining: number;
}

/** ACTIVE + reste > 0 + créée depuis plus d'un jour. */
export function computePrescriptionsToDispense(
  prescriptions: Prescription[],
  ref: Date | null,
): PrescriptionToDispense[] {
  const dayMs = 24 * 60 * 60 * 1000;
  return prescriptions
    .filter((r) => {
      if (r.status !== "ACTIVE") return false;
      if (!ref) return false;
      if (ref.getTime() - Date.parse(r.createdAt) <= dayMs) return false;
      const net = netDispensed(r);
      return r.items.some((item) => (net.get(item.drug) ?? 0) < item.quantity);
    })
    .map((r) => {
      const net = netDispensed(r);
      const remaining = r.items.reduce(
        (sum, item) => sum + (item.quantity - (net.get(item.drug) ?? 0)),
        0,
      );
      return { prescription: r, remaining };
    })
    .sort((a, b) =>
      b.prescription.createdAt.localeCompare(a.prescription.createdAt),
    );
}
