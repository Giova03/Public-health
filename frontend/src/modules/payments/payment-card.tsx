"use client";

/**
 * Module Paiements (E4) — carte du registre + mini-stepper compact.
 * Variante compacte maison (le composant @/components/payment-stepper,
 * non modifiable, reste réservé à la fiche détaillée).
 */

import { motion } from "framer-motion";
import { ChevronRight, CloudUpload } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { PAYMENT_BRANCHES, PAYMENT_STATES } from "@/components/payment-stepper";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import type { PaymentRecord, PaymentState } from "@/lib/types";
import { formatDate, formatTime, formatXof } from "@/lib/types";
import { cn } from "@/lib/utils";
import {
  CHANNEL_CONFIG,
  STATE_BADGE_CLASS,
  STATE_LABELS,
  patientDisplayName,
} from "./payment-helpers";

/** Points de progression compacts (5 états nominaux + branche en rouge). */
export function MiniStateDots({ state }: { state: PaymentState }) {
  const nominal = PAYMENT_STATES as readonly string[];
  const index = nominal.indexOf(state);
  const branch = (PAYMENT_BRANCHES as readonly string[]).includes(state);

  if (branch) {
    return (
      <span className="flex items-center gap-1" aria-hidden="true">
        {nominal.map((s) => (
          <span
            key={s}
            className={cn("h-1.5 w-1.5 rounded-full bg-muted-foreground/25")}
          />
        ))}
        <span className="ml-0.5 h-1.5 w-1.5 rounded-full bg-destructive" />
      </span>
    );
  }

  return (
    <span className="flex items-center gap-1" aria-hidden="true">
      {nominal.map((s, i) => (
        <span
          key={s}
          className={cn(
            "h-1.5 w-1.5 rounded-full transition-colors",
            i < index && "bg-primary/50",
            i === index && "bg-primary ring-2 ring-primary/25",
            i > index && "bg-muted-foreground/25",
          )}
        />
      ))}
    </span>
  );
}

interface PaymentCardProps {
  payment: PaymentRecord;
  /** Nom du miroir patients (fallback si patientName absent). */
  fallbackPatients: { id: string; name: { family: string; given: string } }[];
  index: number;
  onSelect: (id: string) => void;
}

export function PaymentCard({
  payment,
  fallbackPatients,
  index,
  onSelect,
}: PaymentCardProps) {
  const channel = CHANNEL_CONFIG[payment.channel];
  const ChannelIcon: LucideIcon = channel.icon;
  const patient = patientDisplayName(
    payment.patientName,
    fallbackPatients,
    payment.patientId,
  );

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: 0.22,
        ease: "easeOut",
        delay: Math.min(index * 0.04, 0.24),
      }}
      whileHover={{ y: -2 }}
      whileTap={{ scale: 0.99 }}
    >
      <Card
        role="button"
        tabIndex={0}
        aria-label={`Paiement de ${patient} — ${payment.purpose} — ${formatXof(payment.amountXof)}`}
        onClick={() => onSelect(payment.id)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onSelect(payment.id);
          }
        }}
        className="cursor-pointer border-border/80 py-4 transition-all hover:border-primary/40 hover:bg-accent/40 hover:shadow-tile focus-visible:outline-2 focus-visible:outline-ring"
      >
        <div className="flex items-start justify-between gap-3 px-4">
          <div className="min-w-0 flex-1 space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <ChannelIcon className="h-4 w-4" aria-hidden="true" />
              </span>
              <p className="truncate text-sm font-medium text-foreground">
                {patient}
              </p>
              {payment.pendingSync && (
                <Badge
                  variant="outline"
                  className="gap-1 border-amber-600/40 bg-amber-500/10 text-[11px] text-amber-600 dark:border-amber-400/40 dark:text-amber-400"
                >
                  <CloudUpload className="h-3 w-3" aria-hidden="true" />
                  En attente
                </Badge>
              )}
            </div>
            <p className="truncate text-xs text-muted-foreground">
              {payment.purpose} · {channel.short} ·{" "}
              <span className="tnum">
                {formatDate(payment.createdAt)} {formatTime(payment.createdAt)}
              </span>
            </p>
            <MiniStateDots state={payment.state} />
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1.5">
            <p className="tnum text-base font-semibold tracking-tight text-foreground">
              {formatXof(payment.amountXof)}
            </p>
            <Badge variant="outline" className={STATE_BADGE_CLASS[payment.state]}>
              {STATE_LABELS[payment.state]}
            </Badge>
          </div>
          <ChevronRight
            className="mt-1.5 h-4 w-4 shrink-0 text-muted-foreground/60"
            aria-hidden="true"
          />
        </div>
      </Card>
    </motion.div>
  );
}
