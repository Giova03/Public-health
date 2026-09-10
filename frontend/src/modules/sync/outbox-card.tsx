"use client";

/**
 * Module Synchronisation (E2) — file d'attente (outbox), la vedette de la
 * démonstration offline-first : chaque opération créée hors ligne y attend
 * le drain idempotent (opId UUID v7). Ordre de drain = createdAt croissant.
 */

import { useMemo } from "react";
import { AnimatePresence, motion } from "framer-motion";
import type { Variants } from "framer-motion";
import { CircleCheck, Inbox, TriangleAlert } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useAppStore } from "@/lib/store";
import type { SyncOperation } from "@/lib/types";
import { formatDate, formatTime } from "@/lib/types";
import { cn } from "@/lib/utils";
import {
  OPERATION_KIND_CONFIG,
  OPERATION_STATUS_CONFIG,
  operationSubject,
  shortId,
} from "./sync-helpers";

const LIST_VARIANTS: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.04 } },
};

const ITEM_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.16, ease: "easeOut" },
  },
  exit: { opacity: 0, transition: { duration: 0.14 } },
};

export function OutboxCard() {
  const outbox = useAppStore((s) => s.outbox);
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);

  const patientsById = useMemo(
    () => new Map(patients.map((p) => [p.id, p])),
    [patients],
  );
  const prescriptionsById = useMemo(
    () => new Map(prescriptions.map((r) => [r.id, r])),
    [prescriptions],
  );
  const ordered = useMemo(
    () => [...outbox].sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
    [outbox],
  );

  return (
    <Card
      className={cn(
        "gap-4 py-4 sm:py-5 md:py-6",
        ordered.length > 0 && "border-amber-600/40 dark:border-amber-400/40",
      )}
    >
      <CardHeader className="px-4 pb-0 sm:px-6">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="flex size-7 items-center justify-center rounded-full bg-primary/10 text-primary">
            <Inbox className="size-4" aria-hidden="true" />
          </span>
          File d&apos;attente
          <Badge
            variant="outline"
            className={cn(
              "tnum",
              ordered.length > 0
                ? "border-amber-600/40 bg-amber-500/10 text-amber-600 dark:border-amber-400/40 dark:text-amber-400"
                : "text-muted-foreground",
            )}
          >
            {ordered.length}
          </Badge>
        </CardTitle>
        <CardDescription className="text-xs sm:text-sm">
          Opérations créées hors ligne, drainées en lot idempotent (opId UUID
          v7) dès le retour du réseau.
        </CardDescription>
      </CardHeader>

      <CardContent className="px-4 pb-4 sm:px-6">
        {ordered.length === 0 ? (
          <div
            className="flex flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed py-8 text-center"
            role="status"
          >
            <CircleCheck className="size-7 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
            <p className="text-sm font-medium">
              Rien à envoyer — tout est synchronisé
            </p>
            <p className="max-w-xs text-xs text-muted-foreground">
              Les opérations créées hors ligne apparaîtront ici.
            </p>
          </div>
        ) : (
          <motion.ul
            variants={LIST_VARIANTS}
            initial="hidden"
            animate="visible"
            className="max-h-96 space-y-2 overflow-y-auto scrollbar-thin pr-1"
            aria-label="Opérations en attente de synchronisation"
          >
            <AnimatePresence initial={false}>
              {ordered.map((op) => (
                <motion.li key={op.opId} variants={ITEM_VARIANTS}>
                  <OutboxOperation
                    op={op}
                    subject={operationSubject(op, patientsById, prescriptionsById)}
                  />
                </motion.li>
              ))}
            </AnimatePresence>
          </motion.ul>
        )}
      </CardContent>
    </Card>
  );
}

/* ---------------------------- Opération -------------------------------- */

function OutboxOperation({
  op,
  subject,
}: {
  op: SyncOperation;
  subject: string | null;
}) {
  const kindConfig = OPERATION_KIND_CONFIG[op.kind];
  const statusConfig = OPERATION_STATUS_CONFIG[op.status];
  const KindIcon = kindConfig.icon;

  return (
    <div className="rounded-xl border bg-card p-3 transition-all hover:bg-muted/40">
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-start gap-2.5">
          <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
            <KindIcon className="size-4" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-medium leading-tight">
              {kindConfig.label}
            </p>
            {subject && (
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {subject}
              </p>
            )}
          </div>
        </div>

        {op.status === "failed" ? (
          <motion.span
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.2, ease: "easeOut" }}
          >
            <Badge
              variant="outline"
              className={cn("gap-1", statusConfig.badgeClass)}
            >
              <TriangleAlert aria-hidden="true" />
              {statusConfig.label}
            </Badge>
          </motion.span>
        ) : (
          <Badge
            variant="outline"
            className={statusConfig.badgeClass}
          >
            {statusConfig.label}
          </Badge>
        )}
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 pl-[2.625rem] text-[11px] text-muted-foreground">
        <span className="tnum font-mono" title={op.opId}>
          {shortId(op.opId)}
        </span>
        <span className="tnum" title={op.createdAt}>
          {formatDate(op.createdAt)} · {formatTime(op.createdAt)}
        </span>
        <span
          className="tnum"
          title="Tentatives d'envoi depuis la mise en file"
        >
          {op.attempts} tentative{op.attempts > 1 ? "s" : ""}
        </span>
      </div>

      {op.status === "failed" && op.lastError && (
        <motion.p
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.2 }}
          className="mt-1.5 flex items-start gap-1.5 pl-[2.625rem] text-xs text-destructive"
        >
          <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          <span className="min-w-0 break-words">{op.lastError}</span>
        </motion.p>
      )}
    </div>
  );
}
