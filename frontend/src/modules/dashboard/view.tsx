"use client";

/**
 * Module Tableau de bord — vue d'ensemble du poste de santé.
 * 100 % dérivé du miroir local (store) : aucun appel réseau.
 */

import { useMemo } from "react";
import { motion } from "framer-motion";
import {
  ClipboardList,
  Clock,
  HeartPulse,
  MapPin,
  RefreshCw,
  Users,
  Wallet,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { CURRENT_USER } from "@/lib/demo/reference";
import { useAppStore } from "@/lib/store";
import type { ViewId } from "@/lib/types";
import { formatTime, formatXof } from "@/lib/types";
import { ActivityChart } from "./activity-chart";
import {
  computeActivitySeries,
  computeDashboardKpis,
  computeFailedOps,
  computeFailedPayments,
  computePrescriptionsToDispense,
} from "./helpers";
import { KpiCard } from "./kpi-card";
import {
  FailedOpsAlert,
  FailedPaymentsAlert,
  PrescriptionsToDispenseAlert,
} from "./alerts";
import { QuickAccess } from "./quick-access";

function capitalizeFirst(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

const sectionMotion = (delay: number) => ({
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.24, ease: "easeOut" as const, delay },
});

export function DashboardView() {
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);
  const payments = useAppStore((s) => s.payments);
  const outbox = useAppStore((s) => s.outbox);
  const lastSyncAt = useAppStore((s) => s.lastSyncAt);
  const syncing = useAppStore((s) => s.syncing);
  const goTo = useAppStore((s) => s.goTo);
  const hydrated = useIsHydrated();

  /* Date du jour — côté client uniquement (pas d'écart d'hydratation). */
  const today = useMemo(() => (hydrated ? new Date() : null), [hydrated]);

  const kpis = useMemo(
    () => computeDashboardKpis(patients, prescriptions, payments, today),
    [patients, prescriptions, payments, today],
  );
  const series = useMemo(
    () => computeActivitySeries(patients, payments, today),
    [patients, payments, today],
  );
  const failedPayments = useMemo(
    () => computeFailedPayments(payments),
    [payments],
  );
  const failedOps = useMemo(() => computeFailedOps(outbox), [outbox]);
  const toDispense = useMemo(
    () => computePrescriptionsToDispense(prescriptions, today),
    [prescriptions, today],
  );

  const dateLabel = today
    ? capitalizeFirst(
        today.toLocaleDateString("fr-FR", {
          weekday: "long",
          day: "numeric",
          month: "long",
          year: "numeric",
        }),
      )
    : null;
  const firstName = CURRENT_USER.fullName.split(" ")[0];

  return (
    <div className="space-y-4">
      {/* Carte héros — signature maquette : dégradé menthe→teal,
          croix médicales en filigrane, pilules blanches, badge flottant. */}
      <motion.div {...sectionMotion(0)}>
        <div className="relative overflow-hidden rounded-3xl bg-gradient-medical shadow-float">
          <div className="absolute inset-0 bg-plus-pattern" aria-hidden="true" />
          <div
            className="absolute -right-14 -top-20 h-52 w-52 rounded-full bg-white/30 blur-2xl"
            aria-hidden="true"
          />
          <div
            className="absolute -bottom-24 -left-14 h-48 w-48 rounded-full bg-white/25 blur-2xl"
            aria-hidden="true"
          />

          <div className="relative flex flex-wrap items-start justify-between gap-3 p-5 sm:p-6">
            <div className="min-w-0">
              <p className="text-[11px] font-semibold uppercase tracking-widest text-ink-medical/70">
                Poste de santé · Burkina Faso
              </p>
              <h2 className="mt-1.5 text-2xl font-bold leading-tight tracking-tight text-ink-medical sm:text-3xl">
                Votre santé, notre priorité
              </h2>
              <p className="mt-1 text-sm font-medium text-ink-medical/80">
                Bonjour {firstName} · {dateLabel ?? "Chargement du poste…"}
              </p>
            </div>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-white/75 px-3 py-1.5 text-xs font-semibold text-ink-medical backdrop-blur">
              <MapPin className="h-3.5 w-3.5" aria-hidden="true" />
              {CURRENT_USER.facility}
            </span>
          </div>

          <div className="relative flex flex-wrap items-center gap-2.5 px-5 pb-5 sm:px-6 sm:pb-6">
            <button
              type="button"
              onClick={() => goTo("sync")}
              className="inline-flex h-10 min-w-0 flex-1 basis-52 items-center justify-between gap-2 rounded-full bg-white/80 px-4 text-ink-medical backdrop-blur transition-all hover:bg-white active:scale-[0.98] sm:flex-none"
              aria-label="Ouvrir la synchronisation"
            >
              <span className="flex min-w-0 items-center gap-2">
                <RefreshCw
                  className={syncing ? "size-3.5 animate-spin" : "size-3.5"}
                  aria-hidden="true"
                />
                <span className="truncate text-xs font-medium">
                  {lastSyncAt
                    ? `Dernière synchro à ${formatTime(lastSyncAt)}`
                    : "Jamais synchronisé"}
                </span>
              </span>
              <span className="tnum shrink-0 rounded-full bg-ink-medical/10 px-2.5 py-0.5 text-[11px] font-semibold text-ink-medical">
                {outbox.length} op. en attente
              </span>
            </button>
            <Button
              variant="medical"
              size="lg"
              onClick={() => goTo("consultation")}
              className="flex-1 basis-40 sm:flex-none"
            >
              Nouvelle consultation
            </Button>
          </div>

          {/* Badge circulaire flottant façon maquette */}
          <div
            className="absolute -bottom-6 right-8 hidden h-20 w-20 flex-col items-center justify-center rounded-full bg-emerald-600 p-2.5 text-center text-[10px] font-semibold uppercase leading-tight tracking-wide text-white shadow-float ring-4 ring-background sm:flex"
            aria-hidden="true"
          >
            <HeartPulse className="mb-0.5 h-4 w-4" aria-hidden="true" />
            Soins rapides et fiables
          </div>
        </div>
      </motion.div>

      {/* KPI */}
      <motion.div
        {...sectionMotion(0.05)}
        className="grid grid-cols-2 gap-3 pt-2 lg:grid-cols-4"
      >
        <KpiCard
          icon={Users}
          tone="blue"
          label="Patients au miroir"
          value={String(kpis.patientsCount)}
          hint="dossiers synchronisés localement"
        />
        <KpiCard
          icon={ClipboardList}
          tone="amber"
          label="Ordonnances en cours"
          value={String(kpis.activePrescriptions)}
          hint="status ACTIVE"
        />
        <KpiCard
          icon={Clock}
          tone="amber"
          label="Paiements en attente"
          value={String(kpis.pendingPayments)}
          hint="initiés · en cours · autorisés"
        />
        <KpiCard
          icon={Wallet}
          tone="green"
          label="Encaissé aujourd'hui"
          value={formatXof(kpis.encaisseDuJour)}
          hint="reçus + réconciliés du jour"
        />
      </motion.div>

      {/* Graphique d'activité */}
      <motion.div {...sectionMotion(0.1)}>
        <ActivityChart data={series} />
      </motion.div>

      {/* Alertes (si non vides) */}
      {(failedPayments.length > 0 ||
        failedOps.length > 0 ||
        toDispense.length > 0) && (
        <motion.div {...sectionMotion(0.15)} className="space-y-3">
          <h2 className="px-1 text-sm font-semibold text-foreground">
            À traiter
          </h2>
          <FailedPaymentsAlert
            payments={failedPayments}
            onGo={() => goTo("payments")}
          />
          <FailedOpsAlert ops={failedOps} onGo={() => goTo("sync")} />
          <PrescriptionsToDispenseAlert
            items={toDispense}
            onGo={() => goTo("prescriptions")}
          />
        </motion.div>
      )}

      {/* Accès rapides */}
      <motion.div {...sectionMotion(0.2)} className="space-y-3">
        <h2 className="px-1 text-sm font-semibold text-foreground">
          Accès rapides
        </h2>
        <QuickAccess onNavigate={(view: ViewId) => goTo(view)} />
      </motion.div>
    </div>
  );
}
