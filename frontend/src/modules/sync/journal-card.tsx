"use client";

/**
 * Module Synchronisation (E2) — journal : trace lisible des 50 derniers
 * événements du moteur (drain d'outbox, tirage delta, échecs réseau),
 * les plus récents en haut.
 */

import { useMemo } from "react";
import { motion } from "framer-motion";
import { History, Server } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useAppStore } from "@/lib/store";
import type { SyncLogEntry } from "@/lib/types";
import { formatTime } from "@/lib/types";
import { cn } from "@/lib/utils";
import { DIRECTION_CONFIG } from "./sync-helpers";

export function SyncJournalCard() {
  const syncLog = useAppStore((s) => s.syncLog);

  /** Chronologie inverse : plus récentes en haut. */
  const entries = useMemo(
    () => [...syncLog].reverse(),
    [syncLog],
  );

  return (
    <Card className="gap-4 py-4 sm:py-5 md:py-6">
      <CardHeader className="px-4 pb-0 sm:px-6">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="flex size-7 items-center justify-center rounded-full bg-muted text-muted-foreground">
            <Server className="size-4" aria-hidden="true" />
          </span>
          Journal de synchronisation
          <Badge variant="outline" className="tnum text-muted-foreground">
            {entries.length}
          </Badge>
        </CardTitle>
        <CardDescription className="text-xs sm:text-sm">
          Dernières synchronisations (50 entrées conservées), les plus
          récentes en haut.
        </CardDescription>
      </CardHeader>

      <CardContent className="px-4 pb-4 sm:px-6">
        {entries.length === 0 ? (
          <div
            className="flex flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed py-8 text-center"
            role="status"
          >
            <History className="size-7 text-muted-foreground/70" aria-hidden="true" />
            <p className="text-sm font-medium">
              Aucune synchronisation depuis l&apos;ouverture.
            </p>
          </div>
        ) : (
          <ul
            className="max-h-80 divide-y overflow-y-auto scrollbar-thin pr-1"
            aria-label="Journal des synchronisations"
          >
            {entries.map((entry) => (
              <JournalEntryRow key={entry.id} entry={entry} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------ Entrée --------------------------------- */

function JournalEntryRow({ entry }: { entry: SyncLogEntry }) {
  const direction = DIRECTION_CONFIG[entry.direction];
  const DirectionIcon = direction.icon;

  return (
    <motion.li
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.16, ease: "easeOut" }}
      className="flex items-start gap-2.5 py-2.5 first:pt-0 last:pb-0"
    >
      <span
        className={cn(
          "mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full",
          direction.iconWrapClass,
        )}
        title={direction.label}
      >
        <DirectionIcon className="size-3.5" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm leading-snug">{entry.summary}</p>
        <p className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          <span className="tnum" title={entry.at}>
            {formatTime(entry.at)}
          </span>
          <span aria-hidden="true">·</span>
          <span>{direction.label}</span>
        </p>
      </div>
      <span
        className={cn(
          "mt-1.5 size-1.5 shrink-0 rounded-full",
          entry.ok ? "bg-emerald-500" : "bg-destructive",
        )}
        role="img"
        aria-label={entry.ok ? "Succès" : "Échec"}
      />
    </motion.li>
  );
}
