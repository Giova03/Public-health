"use client";

/**
 * Module Tableau de bord — carte KPI compacte (chiffres tabulaires).
 * Pastille circulaire teintée selon la sémantique v0.4 :
 * bleu = information, vert = succès, ambre = en attente, corail = alerte.
 */

import type { LucideIcon } from "lucide-react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

export type KpiTone = "blue" | "green" | "amber" | "coral";

const TONE_CHIP: Record<KpiTone, string> = {
  blue: "bg-primary/10 text-primary",
  green:
    "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  amber: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  coral: "bg-destructive/10 text-destructive",
};

export function KpiCard({
  icon: Icon,
  tone = "blue",
  label,
  value,
  hint,
  className,
}: {
  icon: LucideIcon;
  tone?: KpiTone;
  label: string;
  value: string;
  hint?: string;
  className?: string;
}) {
  return (
    <Card
      className={cn(
        "gap-0 p-4 transition-all hover:shadow-tile",
        className,
      )}
    >
      <div className="flex items-center gap-2.5">
        <span
          className={cn(
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-full",
            TONE_CHIP[tone],
          )}
        >
          <Icon className="h-4 w-4" aria-hidden="true" />
        </span>
        <p className="truncate text-xs font-medium text-muted-foreground">
          {label}
        </p>
      </div>
      <p className="tnum mt-2.5 truncate text-2xl font-bold tracking-tight text-foreground">
        {value}
      </p>
      {hint && (
        <p className="mt-1 truncate text-xs text-muted-foreground">{hint}</p>
      )}
    </Card>
  );
}
