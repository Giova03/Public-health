"use client";

/**
 * Module Paiements (E4) — vue « Registre des paiements ».
 * KPI du jour, filtres par état, recherche, initiation offline-first,
 * fiche détaillée (machine 8 états forward-only) et déclenchement
 * manuel de la réconciliation nocturne (job 23 h, fait-foi).
 */

import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  AlertTriangle,
  Banknote,
  Clock,
  Loader2,
  MoonStar,
  Receipt,
  Search,
  Wallet,
  WifiOff,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { useToast } from "@/hooks/use-toast";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { formatXof } from "@/lib/types";
import { cn } from "@/lib/utils";
import { InitiatePaymentDialog } from "./initiate-dialog";
import { PaymentCard } from "./payment-card";
import { PaymentDetail } from "./payment-detail";
import {
  PAYMENT_FILTERS,
  applyPaymentsFilter,
  computePaymentKpis,
  searchPayments,
  sortPaymentsDesc,
  type PaymentFilter,
} from "./payment-helpers";

/* -------------------------- Petites briques UI ------------------------ */

function KpiCard({
  icon: Icon,
  label,
  labelShort,
  value,
  title,
  tone = "default",
}: {
  icon: LucideIcon;
  label: string;
  /** Libellé compact (mobile étroit) — sinon tronqué. */
  labelShort: string;
  value: string;
  title?: string;
  tone?: "default" | "danger";
}) {
  return (
    <div
      className="rounded-xl border border-border bg-card p-2.5 sm:p-3"
      title={title}
    >
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Icon
          className={cn(
            "h-3.5 w-3.5 shrink-0",
            tone === "danger" && "text-destructive",
          )}
          aria-hidden="true"
        />
        <p className="truncate text-[11px] font-medium uppercase tracking-wide">
          <span className="sm:hidden">{labelShort}</span>
          <span className="hidden sm:inline">{label}</span>
        </p>
      </div>
      <p
        className={cn(
          "tnum mt-1.5 truncate text-sm font-semibold sm:text-lg",
          tone === "danger" ? "text-destructive" : "text-foreground",
        )}
      >
        {value}
      </p>
    </div>
  );
}

function OfflineBanner() {
  return (
    <div
      role="status"
      className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2.5 text-xs text-amber-800 dark:text-amber-200"
    >
      <WifiOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <p>
        Mode hors ligne — les encaissements partent en file d&apos;attente
        (outbox) ; les webhooks opérateur simulés sont refusés jusqu&apos;au
        retour du réseau.
      </p>
    </div>
  );
}

function EmptyRegistry({ onCollect }: { onCollect: () => void }) {
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-3 px-6 py-10 text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Receipt className="h-6 w-6" aria-hidden="true" />
        </span>
        <div>
          <p className="text-sm font-semibold text-foreground">
            Aucun paiement au miroir
          </p>
          <p className="mx-auto mt-1 max-w-sm text-xs text-muted-foreground">
            Encaissez votre premier paiement — la machine à 8 états suit chaque
            transaction, de l&apos;initiation à la réconciliation nocturne.
          </p>
        </div>
        <Button onClick={onCollect} className="h-11">
          <Banknote className="size-4" aria-hidden="true" />
          Encaisser
        </Button>
      </CardContent>
    </Card>
  );
}

function EmptyFilter({ onReset }: { onReset: () => void }) {
  return (
    <Card>
      <CardContent className="flex flex-col items-center gap-2.5 px-6 py-8 text-center">
        <p className="text-sm font-medium text-foreground">
          Aucun paiement ne correspond
        </p>
        <p className="text-xs text-muted-foreground">
          Modifiez le filtre d&apos;état ou la recherche patient / motif.
        </p>
        <Button variant="ghost" size="sm" onClick={onReset} className="h-9">
          Réinitialiser les filtres
        </Button>
      </CardContent>
    </Card>
  );
}

/* ------------------------------- Vue ---------------------------------- */

export function PaymentsView() {
  const payments = useAppStore((s) => s.payments);
  const patients = useAppStore((s) => s.patients);
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const runReconciliation = useAppStore((s) => s.runReconciliation);
  const { toast } = useToast();
  const hydrated = useIsHydrated();
  // P0-3 (audit, étape 3) : initiation = paiement:initier (caisse/ICP/admin),
  // réconciliation = paiement:reconcilier (admin — le run qui fait foi).
  const peutInitier = usePermission("paiement:initier");
  const peutReconcilier = usePermission("paiement:reconcilier");

  const [tab, setTab] = useState<PaymentFilter>("ALL");
  const [query, setQuery] = useState("");
  const [selectedPaymentId, setSelectedPaymentId] = useState<string | null>(
    null,
  );
  const [initiateOpen, setInitiateOpen] = useState(false);
  const [reconciling, setReconciling] = useState(false);

  /* Référence « aujourd'hui » — uniquement côté client (hydratation). */
  const nowRef = useMemo(() => (hydrated ? new Date() : null), [hydrated]);

  const kpis = useMemo(
    () => computePaymentKpis(payments, nowRef),
    [payments, nowRef],
  );
  const sorted = useMemo(() => sortPaymentsDesc(payments), [payments]);
  const counts = useMemo(() => {
    const map = new Map<PaymentFilter, number>();
    for (const f of PAYMENT_FILTERS) {
      map.set(
        f.value,
        applyPaymentsFilter(payments, f.value).length,
      );
    }
    return map;
  }, [payments]);
  const visible = useMemo(
    () => searchPayments(applyPaymentsFilter(sorted, tab), query),
    [sorted, tab, query],
  );

  async function handleReconciliation() {
    if (reconciling) return;
    // Refus EXPLICITE avant le voyage API (403 de toute façon garanti).
    if (!peutReconcilier) {
      toast({
        variant: "destructive",
        title: "Action refusée — réconciliation admin",
        description:
          "La permission paiement:reconcilier est requise : seul l'admin déclenche le run qui fait foi (le job 23 h reste automatique).",
      });
      return;
    }
    setReconciling(true);
    const result = await runReconciliation();
    setReconciling(false);
    if (result.status === "error") {
      toast({
        variant: "destructive",
        title: "Réconciliation impossible",
        description: result.message,
      });
      return;
    }
    toast({
      title: "Réconciliation nocturne exécutée",
      description:
        "SUCCEEDED → RECONCILED (fait-foi 23 h) et revue des orphelins — détail dans le journal de synchronisation.",
    });
  }

  return (
    <div className="space-y-4">
      {!simulatedOnline && <OfflineBanner />}

      {/* En-tête + actions */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="hidden md:flex md:items-center md:gap-3">
          <span
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-medical text-ink-medical shadow-tile"
            aria-hidden="true"
          >
            <Banknote className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-xl font-semibold tracking-tight text-foreground">
              Caisse & paiements
            </h1>
            <p className="text-sm text-muted-foreground">
              Registre FedaPay (simulation) — machine à 8 états, XOF.
            </p>
          </div>
        </div>
        <div className="flex w-full items-center gap-2 md:w-auto">
          <Button
            variant="outline"
            onClick={() => void handleReconciliation()}
            disabled={reconciling || !peutReconcilier}
            className="h-11 flex-1 md:flex-none"
            title={
              peutReconcilier
                ? "Lance le job nocturne simulé (23 h) : réconciliation fait-foi"
                : "Masqué hors admin : paiement:reconcilier requis (le run 23 h reste automatique)"
            }
          >
            {reconciling ? (
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            ) : (
              <MoonStar className="size-4" aria-hidden="true" />
            )}
            <span className="sr-only sm:not-sr-only">Réconciliation</span>
          </Button>
          {peutInitier ? (
            <Button
              variant="medical"
              onClick={() => setInitiateOpen(true)}
              className="h-11 flex-1 md:flex-none"
            >
              <Banknote className="size-4" aria-hidden="true" />
              Encaisser
            </Button>
          ) : (
            <p
              className="flex-1 text-xs text-muted-foreground md:flex-none"
              title="Masquage P0-3 : votre rôle ne porte pas paiement:initier"
            >
              Encaissement masqué : <code>paiement:initier</code> requis — le
              registre reste consultable.
            </p>
          )}
        </div>
      </div>

      {/* KPI du jour */}
      <div className="grid grid-cols-3 gap-2 sm:gap-3">
        <KpiCard
          icon={Wallet}
          label="Encaissé du jour"
          labelShort="Encaissé"
          value={formatXof(kpis.encaisseDuJour)}
          title="Somme des paiements SUCCEEDED et RECONCILED créés aujourd'hui"
        />
        <KpiCard
          icon={Clock}
          label="En attente"
          labelShort="En attente"
          value={formatXof(kpis.enAttente)}
          title={`${kpis.enAttenteCount} paiement(s) INITIATED + PENDING`}
        />
        <KpiCard
          icon={AlertTriangle}
          label="Échecs"
          labelShort="Échecs"
          value={String(kpis.echecs)}
          tone="danger"
          title="Paiements FAILED à revoir"
        />
      </div>

      {/* Filtres + recherche */}
      <div className="space-y-2.5">
        <Tabs
          value={tab}
          onValueChange={(v) => setTab(v as PaymentFilter)}
        >
          <TabsList className="h-auto w-full justify-between overflow-x-auto scrollbar-thin bg-muted/60 p-1">
            {PAYMENT_FILTERS.map((f) => (
              <TabsTrigger
                key={f.value}
                value={f.value}
                className="min-h-9 min-w-0 flex-1 px-2 text-xs data-[state=active]:bg-card sm:px-3 sm:text-sm"
              >
                <span className="truncate">{f.label}</span>
                <span className="tnum ml-1 text-[10px] opacity-60">
                  {counts.get(f.value) ?? 0}
                </span>
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <div className="relative">
          <Search
            className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Rechercher un patient ou un motif…"
            className="h-11 pl-9"
            aria-label="Rechercher un paiement"
            inputMode="search"
          />
        </div>
      </div>

      {/* Liste / fiche */}
      <AnimatePresence mode="wait" initial={false}>
        {selectedPaymentId ? (
          <motion.div
            key="detail"
            initial={{ opacity: 0, x: 24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 24 }}
            transition={{ duration: 0.18, ease: "easeOut" }}
          >
            <PaymentDetail
              paymentId={selectedPaymentId}
              onBack={() => setSelectedPaymentId(null)}
            />
          </motion.div>
        ) : (
          <motion.div
            key="list"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="max-h-[64vh] space-y-2.5 overflow-y-auto scrollbar-thin pr-1"
          >
            {payments.length === 0 ? (
              <EmptyRegistry onCollect={() => setInitiateOpen(true)} />
            ) : visible.length === 0 ? (
              <EmptyFilter
                onReset={() => {
                  setTab("ALL");
                  setQuery("");
                }}
              />
            ) : (
              visible.map((payment, index) => (
                <PaymentCard
                  key={payment.id}
                  payment={payment}
                  fallbackPatients={patients}
                  index={index}
                  onSelect={setSelectedPaymentId}
                />
              ))
            )}
          </motion.div>
        )}
      </AnimatePresence>

      <InitiatePaymentDialog
        open={initiateOpen}
        onOpenChange={setInitiateOpen}
      />
    </div>
  );
}
