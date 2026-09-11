"use client";

/**
 * Module Paiements (E4) — dialogue d'initiation (« Encaisser »).
 * Offline-first : hors ligne, la soumission part dans l'outbox
 * (idempotence par clientRequestId) et revient amber, sans bloquer.
 */

import { useState } from "react";
import { Loader2, Wallet, WifiOff } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/hooks/use-toast";
import { PAYMENT_CHANNELS, PAYMENT_PURPOSES } from "@/lib/demo/reference";
import { useCurrentUser } from "@/lib/session";
import { useAppStore } from "@/lib/store";
import type { PaymentChannel } from "@/lib/types";
import { formatDate, formatXof } from "@/lib/types";
import { CHANNEL_CONFIG } from "./payment-helpers";

interface InitiatePaymentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const NO_PRESCRIPTION = "none";

export function InitiatePaymentDialog({
  open,
  onOpenChange,
}: InitiatePaymentDialogProps) {
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);
  const initiatePayment = useAppStore((s) => s.initiatePayment);
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const { toast } = useToast();
  const user = useCurrentUser();

  const [patientId, setPatientId] = useState(
    () => useAppStore.getState().selectedPatientId ?? "",
  );
  const [purposeCode, setPurposeCode] = useState<string>(
    PAYMENT_PURPOSES[0].code,
  );
  const [amount, setAmount] = useState<string>(
    String(PAYMENT_PURPOSES[0].amountXof),
  );
  const [channel, setChannel] = useState<PaymentChannel>("MOBILE_MONEY");
  const [prescriptionId, setPrescriptionId] = useState<string>(NO_PRESCRIPTION);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const sortedPatients = [...patients].sort((a, b) =>
    a.name.family.localeCompare(b.name.family, "fr"),
  );
  const patientPrescriptions = prescriptions
    .filter((r) => r.patientId === patientId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  const amountValue = Number.parseInt(amount, 10);
  const amountValid = Number.isInteger(amountValue) && amountValue > 0;

  function resetForm() {
    setPatientId(useAppStore.getState().selectedPatientId ?? "");
    setPurposeCode(PAYMENT_PURPOSES[0].code);
    setAmount(String(PAYMENT_PURPOSES[0].amountXof));
    setChannel("MOBILE_MONEY");
    setPrescriptionId(NO_PRESCRIPTION);
    setError(null);
  }

  function handleOpenChange(next: boolean) {
    if (!next) resetForm();
    onOpenChange(next);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    if (!patientId) {
      setError("Choisissez le patient concerné.");
      return;
    }
    if (!amountValid) {
      setError("Le montant doit être un nombre entier de F CFA, supérieur à 0.");
      return;
    }
    const purpose =
      PAYMENT_PURPOSES.find((p) => p.code === purposeCode) ??
      PAYMENT_PURPOSES[0];

    setError(null);
    setSubmitting(true);
    const result = await initiatePayment({
      patientId,
      ...(prescriptionId !== NO_PRESCRIPTION ? { prescriptionId } : {}),
      purpose: purpose.label,
      amountXof: amountValue,
      channel,
      facility: user.facility,
    });
    setSubmitting(false);

    if (result.status === "error") {
      setError(result.message);
      toast({
        variant: "destructive",
        title: "Encaissement refusé",
        description: result.message,
      });
      return;
    }

    if (result.status === "queued") {
      toast({
        title: "Paiement initié hors ligne",
        description:
          "Il partira à la synchronisation (idempotent par clientRequestId).",
        className:
          "border-amber-500/40 bg-amber-50 text-amber-900 dark:bg-amber-950 dark:text-amber-100",
      });
    } else {
      toast({
        title: "Paiement initié",
        description: `${formatXof(amountValue)} · ${purpose.label} — ${channel === "MOBILE_MONEY" ? "envoyé à l'opérateur" : "enregistré en caisse"}.`,
      });
    }
    resetForm();
    onOpenChange(false);
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto scrollbar-thin sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Wallet className="h-4 w-4" aria-hidden="true" />
            </span>
            Encaisser un paiement
          </DialogTitle>
          <DialogDescription>
            Initiation FedaPay (simulation) — machine à 8 états, XOF sans
            centimes.
          </DialogDescription>
        </DialogHeader>

        {!simulatedOnline && (
          <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-800 dark:text-amber-200">
            <WifiOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            <p>
              Hors ligne : le paiement sera enregistré dans le miroir et mis en
              file d&apos;attente — il partira à la synchronisation.
            </p>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          {/* Patient */}
          <div className="space-y-1.5">
            <Label htmlFor="pay-patient">Patient</Label>
            <Select
              value={patientId}
              onValueChange={(v) => {
                setPatientId(v);
                setPrescriptionId(NO_PRESCRIPTION);
              }}
            >
              <SelectTrigger id="pay-patient" className="h-11 w-full">
                <SelectValue placeholder="Choisir un patient du miroir" />
              </SelectTrigger>
              <SelectContent>
                {sortedPatients.map((p) => (
                  <SelectItem key={p.id} value={p.id} className="py-2.5">
                    {p.name.family} {p.name.given}
                    {p.phReference ? (
                      <span className="ml-1.5 text-xs text-muted-foreground">
                        {p.phReference}
                      </span>
                    ) : (
                      <span className="ml-1.5 text-xs text-muted-foreground">
                        réf. à la synchro
                      </span>
                    )}
                  </SelectItem>
                ))}
                {sortedPatients.length === 0 && (
                  <div className="px-2 py-3 text-xs text-muted-foreground">
                    Aucun patient au miroir.
                  </div>
                )}
              </SelectContent>
            </Select>
          </div>

          {/* Motif + montant */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="pay-purpose">Motif</Label>
              <Select
                value={purposeCode}
                onValueChange={(code) => {
                  setPurposeCode(code);
                  const purpose = PAYMENT_PURPOSES.find((p) => p.code === code);
                  if (purpose) setAmount(String(purpose.amountXof));
                }}
              >
                <SelectTrigger id="pay-purpose" className="h-11 w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PAYMENT_PURPOSES.map((p) => (
                    <SelectItem key={p.code} value={p.code} className="py-2.5">
                      {p.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="pay-amount">Montant (F CFA)</Label>
              <Input
                id="pay-amount"
                inputMode="numeric"
                autoComplete="off"
                value={amount}
                onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ""))}
                className="tnum h-11"
                aria-invalid={!amountValid || undefined}
              />
            </div>
          </div>
          {amountValid && amount !== "" && (
            <p className="tnum -mt-2 text-xs text-muted-foreground">
              {formatXof(amountValue)} — tarif indicatif ajustable.
            </p>
          )}

          {/* Ordonnance liée (optionnel) */}
          {patientId && (
            <div className="space-y-1.5">
              <Label htmlFor="pay-prescription">
                Ordonnance liée{" "}
                <span className="font-normal text-muted-foreground">
                  (optionnel)
                </span>
              </Label>
              <Select
                value={prescriptionId}
                onValueChange={setPrescriptionId}
                disabled={patientPrescriptions.length === 0}
              >
                <SelectTrigger id="pay-prescription" className="h-11 w-full">
                  <SelectValue
                    placeholder={
                      patientPrescriptions.length === 0
                        ? "Aucune ordonnance pour ce patient"
                        : "Aucune"
                    }
                  />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_PRESCRIPTION} className="py-2.5">
                    Aucune
                  </SelectItem>
                  {patientPrescriptions.map((r) => (
                    <SelectItem key={r.id} value={r.id} className="py-2.5">
                      {r.diagnosis} ·{" "}
                      <span className="tnum text-xs text-muted-foreground">
                        {formatDate(r.date)}
                      </span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          {/* Canal */}
          <div className="space-y-2">
            <Label>Canal de paiement</Label>
            <RadioGroup
              value={channel}
              onValueChange={(v) => setChannel(v as PaymentChannel)}
              className="grid grid-cols-1 gap-2"
            >
              {PAYMENT_CHANNELS.map((ch) => {
                const config = CHANNEL_CONFIG[ch.value];
                const Icon: LucideIcon = config.icon;
                return (
                  <label
                    key={ch.value}
                    className="group flex min-h-[52px] cursor-pointer items-center gap-3 rounded-xl border border-input p-3 transition-all hover:border-primary/40 hover:bg-accent/50 has-[button[data-state=checked]]:border-primary has-[button[data-state=checked]]:bg-primary/5"
                  >
                    <RadioGroupItem value={ch.value} id={`channel-${ch.value}`} />
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground transition-colors group-has-[button[data-state=checked]]:bg-primary/10 group-has-[button[data-state=checked]]:text-primary">
                      <Icon className="h-4 w-4" aria-hidden="true" />
                    </span>
                    <span className="min-w-0">
                      <span className="block text-sm font-medium leading-tight">
                        {config.short}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {config.hint}
                      </span>
                    </span>
                  </label>
                );
              })}
            </RadioGroup>
          </div>

          {error && (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          )}

          <Separator />

          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              type="button"
              variant="ghost"
              onClick={() => handleOpenChange(false)}
              className="h-12 flex-1 sm:flex-none"
              disabled={submitting}
            >
              Annuler
            </Button>
            <Button
              type="submit"
              className="h-12 flex-1 sm:flex-none"
              disabled={submitting || sortedPatients.length === 0}
            >
              {submitting ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                  Envoi…
                </>
              ) : (
                <>
                  <Wallet className="size-4" aria-hidden="true" />
                  Initier le paiement
                </>
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
