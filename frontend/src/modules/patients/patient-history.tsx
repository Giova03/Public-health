"use client";

/**
 * Module Patients (E1) — historique du dossier : ordonnances (E3) et
 * paiements (E4) lus dans le miroir local, filtrés par patient.
 * Chaque ligne est un raccourci vers le module concerné (navigation
 * mono-page via le store, la sélection patient est conservée).
 */

import { useMemo } from "react";
import { motion } from "framer-motion";
import { ChevronRight, ClipboardList, CreditCard } from "lucide-react";
import type { PaymentRecord, Prescription } from "@/lib/types";
import { formatDate, formatXof } from "@/lib/types";
import { netDispensed, useAppStore } from "@/lib/store";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import {
  PAYMENT_STATE_LABELS,
  PRESCRIPTION_STATUS_LABELS,
} from "./patient-utils";

const ROW_CLASSES =
  "flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-accent/50 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring sm:px-6";

/* ------------------------------ Ordonnances ---------------------------- */

function remainingSummary(prescription: Prescription): string {
  const net = netDispensed(prescription);
  let prescribed = 0;
  let dispensed = 0;
  for (const item of prescription.items) {
    prescribed += item.quantity;
    dispensed += net.get(item.drug) ?? 0;
  }
  const rest = prescribed - dispensed;
  if (rest <= 0) return "dispensée intégralement";
  return `reste ${rest} u. sur ${prescribed}`;
}

/* -------------------------------- Rendu --------------------------------- */

export function PatientHistory({ patientId }: { patientId: string }) {
  const prescriptions = useAppStore((s) => s.prescriptions);
  const payments = useAppStore((s) => s.payments);
  const goTo = useAppStore((s) => s.goTo);
  const selectPrescription = useAppStore((s) => s.selectPrescription);

  const patientPrescriptions = useMemo(
    () =>
      prescriptions
        .filter((p) => p.patientId === patientId)
        .sort((a, b) => b.date.localeCompare(a.date)),
    [prescriptions, patientId],
  );

  const patientPayments = useMemo(
    () =>
      payments
        .filter((p) => p.patientId === patientId)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [payments, patientId],
  );

  return (
    <Card className="overflow-hidden">
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Historique</CardTitle>
      </CardHeader>
      <Tabs defaultValue="prescriptions">
        <div className="px-4 sm:px-6">
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="prescriptions" className="min-h-9">
              Ordonnances
              <Badge variant="secondary" className="tnum ml-1.5 px-1.5 text-[10px]">
                {patientPrescriptions.length}
              </Badge>
            </TabsTrigger>
            <TabsTrigger value="payments" className="min-h-9">
              Paiements
              <Badge variant="secondary" className="tnum ml-1.5 px-1.5 text-[10px]">
                {patientPayments.length}
              </Badge>
            </TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="prescriptions" className="mt-3">
          {patientPrescriptions.length === 0 ? (
            <EmptyHistory
              icon={ClipboardList}
              title="Aucune ordonnance"
              hint="Les consultations de ce patient apparaîtront ici."
            />
          ) : (
            <ul className="max-h-[420px] divide-y overflow-y-auto scrollbar-thin">
              {patientPrescriptions.map((prescription, i) => {
                const status = PRESCRIPTION_STATUS_LABELS[prescription.status];
                return (
                  <li key={prescription.id}>
                    <motion.button
                      type="button"
                      initial={{ opacity: 0, y: 6 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.15, delay: Math.min(i * 0.03, 0.25) }}
                      className={ROW_CLASSES}
                      onClick={() => {
                        selectPrescription(prescription.id);
                        goTo("prescriptions");
                      }}
                    >
                      <span
                        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
                        aria-hidden="true"
                      >
                        <ClipboardList className="h-4 w-4" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">
                          {prescription.diagnosis}
                        </span>
                        <span className="block truncate text-xs text-muted-foreground">
                          <span className="tnum">{formatDate(prescription.date)}</span>
                          {" · "}
                          {prescription.items.length} ligne
                          {prescription.items.length > 1 ? "s" : ""} ·{" "}
                          {remainingSummary(prescription)}
                        </span>
                      </span>
                      <span className="flex shrink-0 flex-col items-end gap-1">
                        <Badge variant="outline" className={status.className}>
                          {status.label}
                        </Badge>
                        <span className="hidden text-[11px] text-muted-foreground sm:block">
                          {prescription.pendingSync ? "à synchroniser" : "synchro ok"}
                        </span>
                      </span>
                      <ChevronRight
                        className="h-4 w-4 shrink-0 text-muted-foreground"
                        aria-hidden="true"
                      />
                    </motion.button>
                  </li>
                );
              })}
            </ul>
          )}
        </TabsContent>

        <TabsContent value="payments" className="mt-3">
          {patientPayments.length === 0 ? (
            <EmptyHistory
              icon={CreditCard}
              title="Aucun paiement"
              hint="Les paiements initiés pour ce patient apparaîtront ici."
            />
          ) : (
            <ul className="max-h-[420px] divide-y overflow-y-auto scrollbar-thin">
              {patientPayments.map((payment, i) => (
                <li key={payment.id}>
                  <motion.button
                    type="button"
                    initial={{ opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.15, delay: Math.min(i * 0.03, 0.25) }}
                    className={ROW_CLASSES}
                    onClick={() => goTo("payments")}
                  >
                    <span
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
                      aria-hidden="true"
                    >
                      <CreditCard className="h-4 w-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">
                        {payment.purpose}
                      </span>
                      <span className="tnum block text-xs text-muted-foreground">
                        {formatDate(payment.createdAt)}
                      </span>
                    </span>
                    <span className="flex shrink-0 flex-col items-end gap-1">
                      <span className="tnum text-sm font-semibold">
                        {formatXof(payment.amountXof)}
                      </span>
                      <PaymentStateBadge payment={payment} />
                    </span>
                    <ChevronRight
                      className="h-4 w-4 shrink-0 text-muted-foreground"
                      aria-hidden="true"
                    />
                  </motion.button>
                </li>
              ))}
            </ul>
          )}
        </TabsContent>
      </Tabs>
    </Card>
  );
}

function PaymentStateBadge({ payment }: { payment: PaymentRecord }) {
  const state = PAYMENT_STATE_LABELS[payment.state];
  return (
    <Badge
      variant="outline"
      className={cn("text-[11px]", state.className)}
    >
      {state.label}
    </Badge>
  );
}

function EmptyHistory({
  icon: Icon,
  title,
  hint,
}: {
  icon: typeof ClipboardList;
  title: string;
  hint: string;
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-6 py-10 text-center">
      <span
        className="flex h-11 w-11 items-center justify-center rounded-full bg-muted"
        aria-hidden="true"
      >
        <Icon className="h-5 w-5 text-muted-foreground" />
      </span>
      <p className="text-sm font-medium">{title}</p>
      <p className="max-w-56 text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}
