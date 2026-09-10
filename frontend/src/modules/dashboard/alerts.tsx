"use client";

/**
 * Module Tableau de bord — cartes d'alerte (mieux vaut visible qu'enterré).
 * Rendues uniquement si non vides : paiements échoués, opérations outbox
 * en échec, ordonnances actives à dispenser depuis plus d'un jour.
 */

import type { ReactNode } from "react";
import {
  AlertTriangle,
  ChevronRight,
  CloudOff,
  ClipboardList,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import type { PatientName, PaymentRecord, SyncOperation } from "@/lib/types";
import { formatDate, formatXof } from "@/lib/types";
import { cn } from "@/lib/utils";
import type { PrescriptionToDispense } from "./helpers";

function nameOf(name: PatientName | undefined): string {
  return name ? `${name.family} ${name.given}` : "Patient";
}

const AMBER_CARD = "border-amber-500/40 bg-amber-500/5";
const AMBER_ICON = "bg-amber-500/15 text-amber-700 dark:text-amber-300";

function AlertCard({
  icon,
  tone,
  title,
  count,
  onAction,
  actionLabel,
  children,
}: {
  icon: ReactNode;
  tone: "amber" | "destructive";
  title: string;
  count: number;
  onAction: () => void;
  actionLabel: string;
  children: ReactNode;
}) {
  return (
    <Card className={cn(AMBER_CARD, tone === "destructive" && "border-destructive/40 bg-destructive/5")}>
      <CardContent className="space-y-3 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2.5">
            <span
              className={cn(
                "flex h-8 w-8 shrink-0 items-center justify-center rounded-full",
                tone === "destructive"
                  ? "bg-destructive/10 text-destructive"
                  : AMBER_ICON,
              )}
            >
              {icon}
            </span>
            <p className="truncate text-sm font-semibold text-foreground">
              {title}
            </p>
          </div>
          <Badge variant="outline" className="tnum shrink-0">
            {count}
          </Badge>
        </div>
        <ul className="space-y-1.5">{children}</ul>
        <Button
          variant="ghost"
          size="sm"
          onClick={onAction}
          className="h-9 w-full justify-between px-3 text-primary hover:bg-primary/10"
        >
          {actionLabel}
          <ChevronRight className="size-4" aria-hidden="true" />
        </Button>
      </CardContent>
    </Card>
  );
}

/* ---------------------- Paiements échoués (destructif) ----------------- */

export function FailedPaymentsAlert({
  payments,
  onGo,
}: {
  payments: PaymentRecord[];
  onGo: () => void;
}) {
  if (payments.length === 0) return null;
  const shown = payments.slice(0, 3);
  return (
    <AlertCard
      icon={<AlertTriangle className="h-4 w-4" aria-hidden="true" />}
      tone="destructive"
      title="Paiements échoués à revoir"
      count={payments.length}
      onAction={onGo}
      actionLabel="Voir les paiements"
    >
      {shown.map((p) => (
        <li
          key={p.id}
          className="flex items-center justify-between gap-2 text-xs text-muted-foreground"
        >
          <span className="min-w-0 truncate">
            {nameOf(p.patientName)} · {p.purpose}
          </span>
          <span className="tnum shrink-0 font-medium text-foreground">
            {formatXof(p.amountXof)}
          </span>
        </li>
      ))}
      {payments.length > shown.length && (
        <li className="text-xs text-muted-foreground">
          + {payments.length - shown.length} autre(s)…
        </li>
      )}
    </AlertCard>
  );
}

/* -------------------- Opérations outbox en échec (ambre) --------------- */

export function FailedOpsAlert({
  ops,
  onGo,
}: {
  ops: SyncOperation[];
  onGo: () => void;
}) {
  if (ops.length === 0) return null;
  const shown = ops.slice(0, 3);
  return (
    <AlertCard
      icon={<CloudOff className="h-4 w-4" aria-hidden="true" />}
      tone="amber"
      title="Opérations de synchronisation en échec"
      count={ops.length}
      onAction={onGo}
      actionLabel="Ouvrir la synchronisation"
    >
      {shown.map((op) => (
        <li
          key={op.opId}
          className="min-w-0 text-xs text-muted-foreground"
        >
          <span className="font-medium text-foreground">{op.kind}</span>
          {op.lastError ? ` — ${op.lastError}` : ""}
        </li>
      ))}
      {ops.length > shown.length && (
        <li className="text-xs text-muted-foreground">
          + {ops.length - shown.length} autre(s)…
        </li>
      )}
    </AlertCard>
  );
}

/* -------------------- Ordonnances à dispenser (ambre) ------------------ */

export function PrescriptionsToDispenseAlert({
  items,
  onGo,
}: {
  items: PrescriptionToDispense[];
  onGo: () => void;
}) {
  if (items.length === 0) return null;
  const shown = items.slice(0, 3);
  return (
    <AlertCard
      icon={<ClipboardList className="h-4 w-4" aria-hidden="true" />}
      tone="amber"
      title="Ordonnances à dispenser"
      count={items.length}
      onAction={onGo}
      actionLabel="Voir les ordonnances"
    >
      {shown.map(({ prescription, remaining }) => (
        <li
          key={prescription.id}
          className="flex items-center justify-between gap-2 text-xs text-muted-foreground"
        >
          <span className="min-w-0 truncate">
            {nameOf(prescription.patientName)} · {prescription.diagnosis} ·{" "}
            <span className="tnum">{formatDate(prescription.date)}</span>
          </span>
          <span className="tnum shrink-0 font-medium text-foreground">
            reste {remaining}
          </span>
        </li>
      ))}
      {items.length > shown.length && (
        <li className="text-xs text-muted-foreground">
          + {items.length - shown.length} autre(s)…
        </li>
      )}
    </AlertCard>
  );
}
