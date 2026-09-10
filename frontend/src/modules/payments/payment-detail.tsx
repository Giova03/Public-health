"use client";

/**
 * Module Paiements (E4) — fiche détaillée d'un paiement.
 * Stepper partagé (composant existant, importé sans modification),
 * timeline des moments clés et actions contextuelles dérivées de
 * PAYMENT_TRANSITIONS (forward-only, forward-only uniquement).
 */

import { useState } from "react";
import { motion } from "framer-motion";
import {
  ArrowLeft,
  Building2,
  ClipboardList,
  CloudUpload,
  Hash,
  Info,
  Loader2,
  Lock,
  User,
  WifiOff,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { PAYMENT_STATES, PaymentStepper } from "@/components/payment-stepper";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/hooks/use-toast";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { useAppStore } from "@/lib/store";
import type { PaymentRecord, PaymentState } from "@/lib/types";
import { formatDate, formatTime, formatXof } from "@/lib/types";
import { cn } from "@/lib/utils";
import {
  ACTION_CONFIG,
  CHANNEL_CONFIG,
  STATE_BADGE_CLASS,
  STATE_LABELS,
  ageFromBirthDate,
  patientDisplayName,
  targetsOf,
} from "./payment-helpers";

interface PaymentDetailProps {
  paymentId: string;
  onBack: () => void;
}

interface TimelineEntry {
  label: string;
  at: string;
  current?: boolean;
}

export function PaymentDetail({ paymentId, onBack }: PaymentDetailProps) {
  const payments = useAppStore((s) => s.payments);
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);
  const progressPayment = useAppStore((s) => s.progressPayment);
  const selectPrescription = useAppStore((s) => s.selectPrescription);
  const goTo = useAppStore((s) => s.goTo);
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const { toast } = useToast();
  const hydrated = useIsHydrated();

  const [busyTarget, setBusyTarget] = useState<PaymentState | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<PaymentState | null>(null);

  const payment = payments.find((p) => p.id === paymentId);

  const nowRef = hydrated ? new Date() : null;
  const patient = payment
    ? patients.find((p) => p.id === payment.patientId)
    : undefined;
  const prescription = payment?.prescriptionId
    ? prescriptions.find((r) => r.id === payment.prescriptionId)
    : undefined;

  if (!payment) {
    return (
      <div className="space-y-3">
        <Button variant="ghost" size="sm" onClick={onBack} className="h-10">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Registre
        </Button>
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">
            Paiement introuvable dans le miroir local.
          </CardContent>
        </Card>
      </div>
    );
  }

  /* Reliure non-nullable pour les fermetures (narrowing non persisté). */
  const record: PaymentRecord = payment;

  const targets = targetsOf(payment.state);
  const channel = CHANNEL_CONFIG[payment.channel];
  const ChannelIcon: LucideIcon = channel.icon;
  const displayName = patientDisplayName(
    payment.patientName,
    patients,
    payment.patientId,
  );
  const age =
    patient && nowRef ? ageFromBirthDate(patient.birthDate, nowRef) : null;

  const timeline: TimelineEntry[] = [
    { label: "Paiement créé", at: payment.createdAt },
    ...(payment.updatedAt !== payment.createdAt
      ? [
          {
            label: "Dernière mise à jour",
            at: payment.updatedAt,
            current: true,
          },
        ]
      : []),
    ...(payment.reconciledAt
      ? [
          {
            label: "Réconcilié (job nocturne 23 h)",
            at: payment.reconciledAt,
            current: true,
          },
        ]
      : []),
  ];

  async function handleProgress(target: PaymentState) {
    setBusyTarget(target);
    const result = await progressPayment(paymentId, target);
    setBusyTarget(null);
    if (result.status === "error") {
      toast({
        variant: "destructive",
        title: "Action refusée",
        description: result.message,
      });
      return;
    }
    toast({
      title: `Paiement ${STATE_LABELS[target].toLowerCase()}`,
      description: `${STATE_LABELS[record.state]} → ${STATE_LABELS[target]} · transition forward-only appliquée.`,
    });
  }

  /* Stepper : états nominaux directs ; REFUNDED remonte à SUCCEEDED ;
     FAILED/CANCELLED quittent la voie nominale (notice dédiée). */
  const nominalStates = PAYMENT_STATES as readonly string[];
  const stepperCurrent: (typeof PAYMENT_STATES)[number] | null =
    nominalStates.includes(payment.state)
      ? (payment.state as (typeof PAYMENT_STATES)[number])
      : payment.state === "REFUNDED"
        ? "SUCCEEDED"
        : null;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.22, ease: "easeOut" }}
      className="space-y-4"
    >
      <Button
        variant="ghost"
        size="sm"
        onClick={onBack}
        className="h-10 gap-1.5 px-2 text-muted-foreground"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Registre des paiements
      </Button>

      {/* En-tête + méta */}
      <Card className="overflow-hidden">
        <CardContent className="space-y-4 p-4 sm:p-6">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-xs font-medium uppercase tracking-wider text-primary">
                {payment.purpose}
              </p>
              <p className="tnum mt-1 text-3xl font-semibold tracking-tight text-foreground">
                {formatXof(payment.amountXof)}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              <Badge
                variant="outline"
                className={STATE_BADGE_CLASS[payment.state]}
              >
                {STATE_LABELS[payment.state]}
              </Badge>
              {payment.pendingSync && (
                <Badge
                  variant="outline"
                  className="gap-1 border-amber-600/40 bg-amber-500/10 text-amber-600 dark:border-amber-400/40 dark:text-amber-400"
                >
                  <CloudUpload className="h-3 w-3" aria-hidden="true" />
                  En attente de synchro
                </Badge>
              )}
            </div>
          </div>

          <Separator />

          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
            <div className="flex items-start gap-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                <User className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">Patient</dt>
                <dd className="truncate font-medium">
                  {displayName}
                  {age !== null && (
                    <span className="ml-1 font-normal text-muted-foreground tnum">
                      · {age} ans
                    </span>
                  )}
                </dd>
              </div>
            </div>
            <div className="flex items-start gap-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                <ChannelIcon className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">Canal</dt>
                <dd className="truncate font-medium">{channel.label}</dd>
              </div>
            </div>
            <div className="flex items-start gap-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                <Building2 className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">Structure</dt>
                <dd className="truncate font-medium">{payment.facility}</dd>
              </div>
            </div>
            <div className="flex items-start gap-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                <Hash className="h-4 w-4" aria-hidden="true" />
              </span>
              <div className="min-w-0">
                <dt className="text-xs text-muted-foreground">
                  Référence interne
                </dt>
                <dd
                  className="truncate font-mono text-xs font-medium text-muted-foreground"
                  title={payment.id}
                >
                  {payment.id.length > 14
                    ? `${payment.id.slice(0, 14)}…`
                    : payment.id}
                </dd>
              </div>
            </div>
            {prescription && (
              <div className="flex items-start gap-2.5 sm:col-span-2">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                  <ClipboardList className="h-4 w-4" aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <dt className="text-xs text-muted-foreground">
                    Ordonnance liée
                  </dt>
                  <dd className="flex flex-wrap items-center gap-2">
                    <span className="truncate font-medium">
                      {prescription.diagnosis} ·{" "}
                      <span className="tnum font-normal text-muted-foreground">
                        {formatDate(prescription.date)}
                      </span>
                    </span>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-8 px-2 text-primary"
                      onClick={() => {
                        selectPrescription(prescription.id);
                        goTo("prescriptions");
                      }}
                    >
                      Ouvrir
                    </Button>
                  </dd>
                </div>
              </div>
            )}
          </dl>

          <Separator />

          {stepperCurrent ? (
            <PaymentStepper current={stepperCurrent} />
          ) : (
            <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-3">
              <p className="text-sm font-medium text-destructive">
                Voie nominale quittée — {STATE_LABELS[payment.state]}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                Branche exceptionnelle irréversible : plus aucune des 5 étapes
                nominales n&apos;est atteignable (machine forward-only).
              </p>
            </div>
          )}
          {payment.state === "REFUNDED" && (
            <p className="text-xs text-amber-700 dark:text-amber-300">
              Encaissement remboursé intégralement (branche REFUNDED, sortie du
              parcours nominal après « Reçu »).
            </p>
          )}
        </CardContent>
      </Card>

      {/* Timeline + actions */}
      <Card>
        <CardContent className="space-y-5 p-4 sm:p-6">
          <div>
            <h2 className="text-sm font-semibold text-foreground">
              Moments clés
            </h2>
            <ol className="relative mt-4 space-y-4 border-l border-border pl-5">
              {timeline.map((entry) => (
                <li key={entry.label} className="relative">
                  <span
                    className={cn(
                      "absolute -left-[27px] top-1 h-2.5 w-2.5 rounded-full border-2 border-card",
                      entry.current ? "bg-primary" : "bg-muted-foreground/40",
                    )}
                    aria-hidden="true"
                  />
                  <p className="text-sm font-medium text-foreground">
                    {entry.label}
                  </p>
                  <p className="tnum text-xs text-muted-foreground">
                    {formatDate(entry.at)} · {formatTime(entry.at)}
                  </p>
                </li>
              ))}
            </ol>
          </div>

          <Separator />

          <div>
            <h2 className="text-sm font-semibold text-foreground">
              Actions contextuelles
            </h2>
            {targets.length === 0 ? (
              <div className="mt-3 flex items-start gap-2 rounded-lg border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
                <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <p>
                  État terminal — machine forward-only : plus aucune transition
                  n&apos;est possible depuis « {STATE_LABELS[payment.state]} ».
                </p>
              </div>
            ) : (
              <div className="mt-3 space-y-2.5">
                {targets
                  .filter((t) => !ACTION_CONFIG[t].danger)
                  .map((target) => (
                    <Button
                      key={target}
                      onClick={() => void handleProgress(target)}
                      disabled={busyTarget !== null}
                      className={cn(
                        "h-12 w-full",
                        target === "RECONCILED" &&
                          "border border-emerald-600/40 bg-transparent text-emerald-600 hover:bg-emerald-500/10 dark:border-emerald-400/40 dark:text-emerald-400",
                      )}
                    >
                      {busyTarget === target && (
                        <Loader2
                          className="size-4 animate-spin"
                          aria-hidden="true"
                        />
                      )}
                      {ACTION_CONFIG[target].label}
                    </Button>
                  ))}
                {targets.filter((t) => ACTION_CONFIG[t].danger).length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {targets
                      .filter((t) => ACTION_CONFIG[t].danger)
                      .map((target) => (
                        <AlertDialog
                          key={target}
                          open={confirmTarget === target}
                          onOpenChange={(open) =>
                            setConfirmTarget(open ? target : null)
                          }
                        >
                          <AlertDialogTrigger asChild>
                            <Button
                              variant="outline"
                              disabled={busyTarget !== null}
                              className="h-11 flex-1 border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive"
                            >
                              {busyTarget === target && (
                                <Loader2
                                  className="size-4 animate-spin"
                                  aria-hidden="true"
                                />
                              )}
                              {ACTION_CONFIG[target].label}
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent>
                            <AlertDialogHeader>
                              <AlertDialogTitle>
                                {ACTION_CONFIG[target].label} ?
                              </AlertDialogTitle>
                              <AlertDialogDescription>
                                {ACTION_CONFIG[target].description} Montant
                                concerné :{" "}
                                <span className="tnum">
                                  {formatXof(payment.amountXof)}
                                </span>
                                .
                              </AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel className="h-10">
                                Retour
                              </AlertDialogCancel>
                              <AlertDialogAction
                                className="h-10 bg-destructive text-white hover:bg-destructive/90"
                                onClick={() => void handleProgress(target)}
                              >
                                Confirmer
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      ))}
                  </div>
                )}
              </div>
            )}

            {!simulatedOnline && targets.length > 0 && (
              <div className="mt-3 flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-800 dark:text-amber-200">
                <WifiOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                <p>
                  Hors ligne : le store refuse ces actions réseau — le paiement
                  reprendra sa progression à la reconnexion.
                </p>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      <p className="flex items-start gap-2 px-1 pb-2 text-xs text-muted-foreground">
        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        Simulation des webhooks FedaPay — en production, ces transitions
        arrivent signées (HMAC) du côté opérateur.
      </p>
    </motion.div>
  );
}
