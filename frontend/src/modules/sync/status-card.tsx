"use client";

/**
 * Module Synchronisation (E2) — carte d'état du moteur : indicateur binaire
 * en ligne / hors ligne, simulation de coupure réseau, identité de
 * l'appareil, curseur delta et déclenchement manuel de la synchronisation.
 *
 * Règle d'or : l'état affiché est TOUJOURS celui du moteur, jamais un
 * optimisme — le bouton est inactif hors ligne et le retour du réseau
 * relance la synchronisation automatiquement (comportement du store).
 */

import { motion } from "framer-motion";
import {
  ClipboardList,
  CreditCard,
  Info,
  MonitorSmartphone,
  RefreshCw,
  Users,
  Wifi,
  WifiOff,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { useToast } from "@/hooks/use-toast";
import { DEVICE, useAppStore } from "@/lib/store";
import { formatDate, formatTime } from "@/lib/types";
import { cn } from "@/lib/utils";
import { shortId } from "./sync-helpers";

/* --------------------------- État dérivé ------------------------------- */

type EngineState = "online" | "offline" | "syncing";

interface MirrorChip {
  icon: LucideIcon;
  label: string;
  count: number;
}

/* ------------------------------ Composant ------------------------------ */

export function SyncStatusCard() {
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const syncing = useAppStore((s) => s.syncing);
  const toggleOnline = useAppStore((s) => s.toggleOnline);
  const syncNow = useAppStore((s) => s.syncNow);
  const outboxCount = useAppStore((s) => s.outbox.length);
  const cursor = useAppStore((s) => s.cursor);
  const lastSyncAt = useAppStore((s) => s.lastSyncAt);
  const deviceId = useAppStore((s) => s.deviceId);
  const patientCount = useAppStore((s) => s.patients.length);
  const prescriptionCount = useAppStore((s) => s.prescriptions.length);
  const paymentCount = useAppStore((s) => s.payments.length);
  const { toast } = useToast();

  const state: EngineState = !simulatedOnline
    ? "offline"
    : syncing
      ? "syncing"
      : "online";

  const indicatorClass =
    state === "offline"
      ? "border-destructive/30 bg-destructive/10 text-destructive"
      : state === "syncing"
        ? "border-primary/30 bg-primary/10 text-primary"
        : "border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400";

  const statusTitle =
    state === "offline"
      ? "Hors ligne"
      : state === "syncing"
        ? "Synchronisation en cours"
        : "En ligne";

  const statusSub =
    state === "offline"
      ? "Les consultations continuent — tout part en file d'attente."
      : state === "syncing"
        ? "Drain de l'outbox puis tirage delta en cours…"
        : "Les données circulent entre l'appareil et le serveur.";

  const mirrorChips: MirrorChip[] = [
    { icon: Users, label: "patients", count: patientCount },
    { icon: ClipboardList, label: "ordonnances", count: prescriptionCount },
    { icon: CreditCard, label: "paiements", count: paymentCount },
  ];

  /**
   * Compte rendu honnête : on dérive le bilan de syncLog (nouvelles entrées
   * apparues pendant l'appel) plutôt que d'affirmer un succès aveugle.
   */
  async function handleSyncNow() {
    const beforeIds = new Set(
      useAppStore.getState().syncLog.map((entry) => entry.id),
    );
    await syncNow();
    const entries = useAppStore
      .getState()
      .syncLog.filter((entry) => !beforeIds.has(entry.id));
    const failures = entries.filter((entry) => !entry.ok);

    if (failures.length > 0) {
      toast({
        variant: "destructive",
        title: "Synchronisation terminée — avec échecs",
        description: `${failures[0].summary}${
          failures.length > 1 ? ` (+${failures.length - 1} autre échec)` : ""
        }`,
      });
      return;
    }
    if (entries.length > 0) {
      const uplinks = entries.filter((e) => e.direction === "uplink").length;
      const downlinks = entries.length - uplinks;
      toast({
        title: "Synchronisation terminée",
        description: `${entries.length} événement(s) : ${uplinks} envoi(s), ${downlinks} réception(s). Détail dans le journal.`,
      });
      return;
    }
    toast({
      title: "Rien à synchroniser",
      description:
        "Aucun nouvel événement : file vide et delta serveur déjà à jour.",
    });
  }

  return (
    <Card
      className={cn(
        "gap-4 py-4 sm:py-5 md:py-6",
        state === "offline" && "border-destructive/40",
      )}
    >
      <CardContent className="space-y-4 px-4 sm:px-6">
        {/* Indicateur binaire + simulation réseau */}
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div
            className="flex min-w-0 items-center gap-3"
            role="status"
            aria-label={`État du moteur : ${statusTitle}`}
          >
            <motion.span
              key={state}
              initial={{ scale: 0.85, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
              className={cn(
                "flex size-12 shrink-0 items-center justify-center rounded-full border",
                indicatorClass,
              )}
            >
              {state === "offline" ? (
                <WifiOff className="size-6" aria-hidden="true" />
              ) : state === "syncing" ? (
                <RefreshCw className="size-6 animate-spin" aria-hidden="true" />
              ) : (
                <Wifi className="size-6" aria-hidden="true" />
              )}
            </motion.span>
            <div className="min-w-0">
              <p className="text-lg font-semibold leading-tight tracking-tight sm:text-xl">
                {statusTitle}
              </p>
              <p className="mt-0.5 text-sm leading-snug text-muted-foreground">
                {statusSub}
              </p>
            </div>
          </div>

          <label
            htmlFor="sync-network-switch"
            className={cn(
              "flex cursor-pointer select-none items-center gap-3 rounded-xl border px-3 py-3 transition-colors",
              simulatedOnline
                ? "border-border bg-muted/40"
                : "border-destructive/40 bg-destructive/5",
            )}
          >
            <span className="text-sm font-medium leading-none">
              Réseau (simulation)
            </span>
            <Switch
              id="sync-network-switch"
              checked={simulatedOnline}
              onCheckedChange={() => toggleOnline()}
              aria-label="Réseau (simulation) : couper ou rétablir la connexion"
              className="h-6 w-11 *:data-[slot=switch-thumb]:size-5"
            />
          </label>
        </div>

        {/* Légende pédagogique */}
        <p className="flex items-start gap-1.5 text-xs leading-relaxed text-muted-foreground">
          <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          Coupez le réseau pour vivre le mode hors ligne : les consultations
          continuent, tout part en file d'attente.
        </p>

        <Separator />

        {/* Identité appareil · dernière synchro · curseur · miroir */}
        <dl className="grid grid-cols-2 gap-x-4 gap-y-4 sm:grid-cols-4">
          <div className="min-w-0">
            <dt className="flex items-center gap-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground/80">
              <MonitorSmartphone className="size-3.5" aria-hidden="true" />
              Appareil
            </dt>
            <dd className="mt-1.5 min-w-0">
              <span
                className="block truncate text-sm font-medium"
                title={deviceId ? `${DEVICE} · ${deviceId}` : DEVICE}
              >
                {DEVICE}
              </span>
              <span
                className="tnum block truncate font-mono text-xs text-muted-foreground"
                title={deviceId ?? undefined}
              >
                {deviceId ? shortId(deviceId) : "—"}
              </span>
            </dd>
          </div>

          <div className="min-w-0">
            <dt className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground/80">
              Dernière synchro
            </dt>
            <dd className="mt-1.5 min-w-0">
              {lastSyncAt ? (
                <span
                  className="tnum block text-sm"
                  title={lastSyncAt}
                >
                  {formatDate(lastSyncAt)} · {formatTime(lastSyncAt)}
                </span>
              ) : (
                <span className="block text-sm text-muted-foreground">
                  Jamais synchronisé
                </span>
              )}
            </dd>
          </div>

          <div className="min-w-0">
            <dt
              className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground/80"
              title="Position du curseur dans le journal delta serveur — avance à chaque tirage réussi"
            >
              Curseur delta
            </dt>
            <dd className="mt-1.5">
              <Badge
                variant="outline"
                className="tnum font-mono"
                title="Position dans le journal serveur"
              >
                {cursor}
              </Badge>
            </dd>
          </div>

          <div className="min-w-0">
            <dt
              className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground/80"
              title="Contenu du miroir IndexedDB (3 zones : patients, ordonnances, paiements)"
            >
              Miroir local
            </dt>
            <dd className="mt-1.5 flex flex-wrap gap-1.5">
              {mirrorChips.map((chip) => (
                <Badge
                  key={chip.label}
                  variant="outline"
                  className="tnum gap-1 text-muted-foreground"
                >
                  <chip.icon aria-hidden="true" />
                  {chip.count} {chip.label}
                </Badge>
              ))}
            </dd>
          </div>
        </dl>

        {/* Déclenchement manuel */}
        <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-1.5">
            <Button
              size="lg"
              variant="medical"
              className="h-11 w-full sm:w-auto"
              disabled={!simulatedOnline || syncing}
              onClick={() => void handleSyncNow()}
            >
              <RefreshCw
                className={cn(syncing && "animate-spin")}
                aria-hidden="true"
              />
              Synchroniser maintenant
            </Button>
            {!simulatedOnline && (
              <p className="text-xs text-muted-foreground">
                Hors ligne : rebranchez le réseau — la synchronisation
                repartira automatiquement.
              </p>
            )}
          </div>
          <p className="tnum text-xs text-muted-foreground sm:max-w-64 sm:text-right">
            {outboxCount > 0
              ? `${outboxCount} opération(s) partiront au prochain drain.`
              : "La file d'attente est vide."}
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
