"use client";

/**
 * Module Synchronisation (E2) — helpers purs : libellés humains des opérations,
 * badges de statut, directions du journal et extraction « garde-fou » du sujet
 * d'une opération (le payload du contrat E2 est typé `unknown` : tout accès
 * est vérifié au runtime, aucune hypothèse).
 */

import {
  CloudDownload,
  CloudUpload,
  ClipboardList,
  CreditCard,
  Pill,
  Undo2,
  UserPen,
  UserPlus,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type {
  OperationKind,
  OperationStatus,
  Patient,
  Prescription,
  SyncLogEntry,
  SyncOperation,
} from "@/lib/types";
import { formatXof } from "@/lib/types";

/* ------------------------- Types d'opérations -------------------------- */

export interface OperationKindConfig {
  /** Libellé humain affiché dans la file d'attente. */
  label: string;
  icon: LucideIcon;
}

export const OPERATION_KIND_CONFIG: Record<OperationKind, OperationKindConfig> = {
  "patient.create": { label: "Création patient", icon: UserPlus },
  "patient.update": { label: "Mise à jour patient", icon: UserPen },
  "prescription.create": { label: "Rédaction ordonnance", icon: ClipboardList },
  "prescription.dispense": { label: "Dispensation", icon: Pill },
  "prescription.counterEntry": { label: "Contre-entrée", icon: Undo2 },
  "payment.initiate": { label: "Initiation paiement", icon: CreditCard },
};

/* --------------------------- Statuts outbox ---------------------------- */

export interface OperationStatusConfig {
  label: string;
  /** Classes du badge (tokens + teintes discrètes cohérentes avec l'app). */
  badgeClass: string;
}

export const OPERATION_STATUS_CONFIG: Record<OperationStatus, OperationStatusConfig> = {
  queued: {
    label: "En file",
    badgeClass:
      "border-amber-600/30 bg-amber-500/10 text-amber-600 dark:border-amber-400/30 dark:text-amber-400",
  },
  sent: {
    label: "Envoyée",
    badgeClass: "border-primary/30 bg-primary/10 text-primary",
  },
  acked: {
    label: "Acquittée",
    badgeClass:
      "border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400",
  },
  failed: {
    label: "Échec",
    badgeClass: "border-destructive/30 bg-destructive/10 text-destructive",
  },
};

/* ---------------------------- Journal (E2) ----------------------------- */

export interface DirectionConfig {
  label: string;
  icon: LucideIcon;
  iconWrapClass: string;
}

export const DIRECTION_CONFIG: Record<SyncLogEntry["direction"], DirectionConfig> = {
  uplink: {
    label: "Envoi (outbox)",
    icon: CloudUpload,
    iconWrapClass: "bg-primary/10 text-primary",
  },
  downlink: {
    label: "Réception (delta)",
    icon: CloudDownload,
    iconWrapClass: "bg-muted text-muted-foreground",
  },
};

/* ------------------------------ Utilitaires ---------------------------- */

/**
 * Identifiant tronqué lisible — un UUID v7 commence par son horodatage :
 * les 8 premiers caractères suffisent à identifier l'opération à l'écran.
 */
export function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.trim().length > 0 ? value : null;
}

function patientLabelOf(patient: Patient | undefined): string | null {
  return patient ? `${patient.name.family} ${patient.name.given}` : null;
}

/**
 * Sujet humain d'une opération (patient concerné, diagnostic, motif et
 * montant du paiement…). Les noms sont résolus via le miroir local ;
 * retourne null lorsque le payload ne permet rien d'affirmer.
 */
export function operationSubject(
  op: SyncOperation,
  patientsById: Map<string, Patient>,
  prescriptionsById: Map<string, Prescription>,
): string | null {
  if (!isRecord(op.payload)) return null;
  const payload = op.payload;

  switch (op.kind) {
    case "patient.create":
    case "patient.update": {
      if (!isRecord(payload.name)) return null;
      const family = asString(payload.name.family);
      const given = asString(payload.name.given);
      return family && given ? `${family} ${given}` : null;
    }
    case "prescription.create": {
      const diagnosis = asString(payload.diagnosis);
      if (diagnosis) return diagnosis;
      const patient = patientsById.get(asString(payload.patientId) ?? "");
      return patientLabelOf(patient);
    }
    case "prescription.dispense":
    case "prescription.counterEntry": {
      const prescription = prescriptionsById.get(
        asString(payload.prescriptionId) ?? "",
      );
      const patient = prescription
        ? patientsById.get(prescription.patientId)
        : undefined;
      const subject = patientLabelOf(patient);
      if (subject) return subject;
      const lines = Array.isArray(payload.lines) ? payload.lines.length : 0;
      return lines > 0 ? `${lines} ligne(s)` : null;
    }
    case "payment.initiate": {
      const purpose = asString(payload.purpose);
      const amount =
        typeof payload.amountXof === "number"
          ? formatXof(payload.amountXof)
          : null;
      const parts = [purpose, amount].filter(
        (part): part is string => part !== null,
      );
      return parts.length > 0 ? parts.join(" · ") : null;
    }
    default:
      return null;
  }
}
