"use client";

import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
  ArrowLeft,
  CheckCircle2,
  CloudOff,
  History,
  Package,
  Pill,
  Store,
  Undo2,
  User,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import type { DispenseLine, Prescription } from "@/lib/types";
import { formatDate, formatTime } from "@/lib/types";
import { CURRENT_USER } from "@/lib/demo/reference";
import { useToast } from "@/hooks/use-toast";
import { useAppStore } from "@/lib/store";
import { cn } from "@/lib/utils";
import { DispenseDialog } from "./dispense-dialog";
import {
  STATUS_BADGE_CLASS,
  STATUS_LABELS,
  counteredIds,
  netOf,
  remainingOf,
} from "./prescription-helpers";

/**
 * Fiche ordonnance (E3) — le cœur métier de la dispensation :
 * lignes avec reste cumulé, dispensation partielle, historique
 * append-only et contre-entrées tracées.
 */

interface PrescriptionDetailProps {
  prescription: Prescription;
  onBack: () => void;
  onOpenPatient: (patientId: string) => void;
}

export function PrescriptionDetail({
  prescription,
  onBack,
  onOpenPatient,
}: PrescriptionDetailProps) {
  const { toast } = useToast();
  const dispense = useAppStore((s) => s.dispense);
  const counterEntry = useAppStore((s) => s.counterEntry);

  const [dispenseOpen, setDispenseOpen] = useState(false);
  const [counterTarget, setCounterTarget] = useState<string | null>(null);

  const net = useMemo(() => netOf(prescription), [prescription]);
  const countered = useMemo(() => counteredIds(prescription), [prescription]);
  const patient = prescription.patientName
    ? `${prescription.patientName.family} ${prescription.patientName.given}`
    : "Patient inconnu";
  const hasRemaining = prescription.items.some(
    (item) => remainingOf(prescription, item.drug, item.quantity) > 0,
  );

  const handleDispense = async (
    lines: DispenseLine[],
    source: "INTERNE" | "PRIVE",
  ) => {
    const result = await dispense({
      prescriptionId: prescription.id,
      pharmacist: CURRENT_USER.fullName,
      source,
      lines,
    });
    if (result.status === "ok") {
      toast({
        title: "Dispensation enregistrée",
        description: `${lines.length} ligne(s) remise(s) à ${patient}.`,
      });
    } else if (result.status === "queued") {
      toast({
        title: "Dispensation enregistrée hors ligne",
        description:
          "Elle partira à la synchronisation — l'historique local la montre déjà.",
      });
    } else {
      toast({
        title: "Dispensation refusée",
        description: result.message,
        variant: "destructive",
      });
    }
  };

  const handleCounterEntry = async (dispenseId: string) => {
    setCounterTarget(null);
    const result = await counterEntry({
      prescriptionId: prescription.id,
      dispenseId,
      pharmacist: CURRENT_USER.fullName,
    });
    if (result.status === "ok") {
      toast({
        title: "Contre-entrée tracée",
        description:
          "La dispensation reste dans l'historique, annulée par une contre-entrée (append-only).",
      });
    } else if (result.status === "queued") {
      toast({
        title: "Contre-entrée enregistrée hors ligne",
        description: "Elle partira à la synchronisation.",
      });
    } else {
      toast({
        title: "Contre-entrée refusée",
        description: result.message,
        variant: "destructive",
      });
    }
  };

  return (
    <div className="space-y-4">
      <div>
        <Button
          variant="ghost"
          className="h-11 gap-1.5 px-2 text-muted-foreground"
          onClick={onBack}
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Toutes les ordonnances
        </Button>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 items-center gap-3">
              <span
                className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
                aria-hidden="true"
              >
                <User className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <button
                  type="button"
                  className="truncate text-left font-semibold hover:text-primary"
                  onClick={() => onOpenPatient(prescription.patientId)}
                  aria-label={`Ouvrir le dossier de ${patient}`}
                >
                  {patient}
                </button>
                <p className="truncate text-xs text-muted-foreground">
                  {prescription.diagnosis} · {formatDate(prescription.date)}
                </p>
              </div>
            </div>
            <div className="flex flex-col items-end gap-1.5">
              <Badge
                variant="outline"
                className={cn(STATUS_BADGE_CLASS[prescription.status])}
              >
                {prescription.status === "COMPLETED" && (
                  <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                )}
                {STATUS_LABELS[prescription.status]}
              </Badge>
              {prescription.pendingSync && (
                <Badge
                  variant="outline"
                  className="gap-1 border-amber-500/40 bg-amber-500/10 text-[10px] text-amber-600 dark:text-amber-400"
                  title="Créée hors ligne, en attente de synchronisation"
                >
                  <CloudOff className="h-3 w-3" aria-hidden="true" />
                  En attente
                </Badge>
              )}
            </div>
          </div>
          <CardDescription>
            {prescription.prescriber} · {prescription.facility}
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-5">
          {/* Lignes prescrites */}
          <div className="space-y-3">
            <p className="text-sm font-medium">Lignes prescrites</p>
            <ul className="space-y-3">
              {prescription.items.map((item) => {
                const done = Math.min(item.quantity, net.get(item.drug) ?? 0);
                const remaining = Math.max(0, item.quantity - done);
                return (
                  <li key={item.drug} className="space-y-2 rounded-xl border p-3.5">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium">{item.drug}</p>
                        <p className="text-xs text-muted-foreground">
                          {item.dosage} · {item.frequency} · {item.durationDays} j
                        </p>
                      </div>
                      <Badge
                        variant="outline"
                        className={cn(
                          "shrink-0 tnum",
                          remaining === 0
                            ? "border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                            : "border-border bg-muted/50 text-muted-foreground",
                        )}
                      >
                        {remaining === 0 ? (
                          <>
                            <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                            Soldée
                          </>
                        ) : (
                          `Reste ${remaining}`
                        )}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-3">
                      <Progress
                        value={Math.round((done / item.quantity) * 100)}
                        aria-label={`Dispensé : ${done} sur ${item.quantity}`}
                        className="flex-1"
                      />
                      <span className="shrink-0 text-xs text-muted-foreground tnum">
                        {done}/{item.quantity}
                      </span>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>

          <Button
            variant="medical"
            className="h-11 w-full sm:w-auto"
            onClick={() => setDispenseOpen(true)}
            disabled={prescription.status !== "ACTIVE" || !hasRemaining}
          >
            <Package className="h-4 w-4" aria-hidden="true" />
            Dispenser
          </Button>

          <Separator />

          {/* Historique append-only */}
          <div className="space-y-3">
            <p className="flex items-center gap-2 text-sm font-medium">
              <History className="h-4 w-4 text-primary" aria-hidden="true" />
              Historique ({prescription.dispenses.length})
            </p>
            {prescription.dispenses.length === 0 ? (
              <p className="rounded-xl border border-dashed p-4 text-sm text-muted-foreground">
                Aucune dispensation enregistrée. L&apos;historique est
                append-only : rien ne s&apos;efface, une erreur se
                contre-entre.
              </p>
            ) : (
              <ul className="space-y-2.5">
                {prescription.dispenses.map((event, index) => {
                  const isCounter = Boolean(event.counterEntryOf);
                  const isCountered = countered.has(event.id);
                  return (
                    <motion.li
                      key={event.id}
                      initial={{ opacity: 0, x: -6 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ duration: 0.15, delay: Math.min(index * 0.04, 0.2) }}
                      className={cn(
                        "rounded-xl border p-3.5",
                        isCounter && "border-destructive/40 bg-destructive/5",
                        isCountered && "opacity-60",
                      )}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex min-w-0 items-center gap-2">
                          {isCounter ? (
                            <Undo2
                              className="h-4 w-4 shrink-0 text-destructive"
                              aria-hidden="true"
                            />
                          ) : (
                            <Pill
                              className="h-4 w-4 shrink-0 text-primary"
                              aria-hidden="true"
                            />
                          )}
                          <p className="truncate text-sm font-medium">
                            {isCounter ? "Contre-entrée" : "Dispensation"}{" "}
                            <span className="font-normal text-muted-foreground tnum">
                              {formatDate(event.at)} {formatTime(event.at)}
                            </span>
                          </p>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <Badge variant="outline" className="gap-1 text-[10px]">
                            {event.source === "PRIVE" ? (
                              <>
                                <Store className="h-3 w-3" aria-hidden="true" />
                                Officine
                              </>
                            ) : (
                              <>
                                <Pill className="h-3 w-3" aria-hidden="true" />
                                Interne
                              </>
                            )}
                          </Badge>
                          {!isCounter && !isCountered && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-8 gap-1 px-2 text-xs text-muted-foreground hover:text-destructive"
                              onClick={() => setCounterTarget(event.id)}
                              aria-label="Contre-entrer cette dispensation"
                            >
                              <Undo2 className="h-3.5 w-3.5" aria-hidden="true" />
                              Contre-entrée
                            </Button>
                          )}
                          {isCountered && (
                            <Badge
                              variant="outline"
                              className="border-destructive/30 bg-destructive/10 text-[10px] text-destructive"
                            >
                              Annulée
                            </Badge>
                          )}
                        </div>
                      </div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {event.pharmacist} ·{" "}
                        {event.lines
                          .map((l) => `${l.drug} ×${l.quantity}`)
                          .join(", ")}
                      </p>
                    </motion.li>
                  );
                })}
              </ul>
            )}
          </div>
        </CardContent>
      </Card>

      <DispenseDialog
        prescription={prescription}
        open={dispenseOpen}
        onOpenChange={setDispenseOpen}
        onConfirm={handleDispense}
      />

      <AlertDialog
        open={counterTarget !== null}
        onOpenChange={(open) => !open && setCounterTarget(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Contre-entrer cette dispensation ?</AlertDialogTitle>
            <AlertDialogDescription>
              La dispensation reste dans l&apos;historique (append-only) et une
              contre-entrée tracée annule ses quantités. Le reste à dispenser
              est recalculé immédiatement.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="h-11">Annuler</AlertDialogCancel>
            <AlertDialogAction
              className="h-11"
              onClick={() =>
                counterTarget && handleCounterEntry(counterTarget)
              }
            >
              <Undo2 className="h-4 w-4" aria-hidden="true" />
              Contre-entrer
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
