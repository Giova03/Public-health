"use client";

/**
 * Module Portail patient — l'espace du patient (profil PATIENT).
 *
 * Contrairement au poste de santé (AppShell), le portail ne montre QUE les
 * données du patient connecté : son identité (MPI), ses ordonnances avec
 * l'avancement réel de la dispensation, et ses paiements. C'est le pendant
 * « patient d'abord » de la plateforme : le patient voit ce que les
 * professionnels enregistrent à son sujet, sur le même miroir local.
 */

import { useMemo } from "react";
import { motion } from "framer-motion";
import {
  Banknote,
  CalendarDays,
  ClipboardList,
  HeartPulse,
  IdCard,
  LogOut,
  MapPin,
  Moon,
  Pill,
  Sun,
  Wallet,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useTheme } from "next-themes";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { useSessionStore } from "@/lib/session";
import { useAppStore } from "@/lib/store";
import type { Patient, PaymentRecord, Prescription } from "@/lib/types";
import { formatDate, formatXof } from "@/lib/types";
import {
  CHANNEL_CONFIG,
  STATE_BADGE_CLASS,
  STATE_LABELS,
} from "@/modules/payments/payment-helpers";
import {
  STATUS_BADGE_CLASS,
  STATUS_LABELS,
  advancement,
  remainingOf,
} from "@/modules/prescriptions/prescription-helpers";

const sectionMotion = (delay: number) => ({
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.24, ease: "easeOut" as const, delay },
});

function ageFrom(iso: string): number {
  const birth = new Date(iso);
  const now = new Date();
  let age = now.getFullYear() - birth.getFullYear();
  const m = now.getMonth() - birth.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) age--;
  return age;
}

/* -------------------------------------------------------------------- */
/* Portail patient                                                      */
/* -------------------------------------------------------------------- */

export function PatientPortal() {
  const session = useSessionStore((s) => s.session);
  const logout = useSessionStore((s) => s.logout);
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);
  const payments = useAppStore((s) => s.payments);

  const { resolvedTheme, setTheme } = useTheme();
  const hydrated = useIsHydrated();
  const isDark = hydrated && resolvedTheme === "dark";

  const me: Patient | null = useMemo(
    () =>
      (session && session.profile === "PATIENT"
        ? patients.find((p) => p.id === session.patientId) ?? null
        : null),
    [session, patients],
  );

  const myPrescriptions = useMemo(
    () =>
      session && session.profile === "PATIENT"
        ? prescriptions
            .filter((r) => r.patientId === session.patientId)
            .sort((a, b) => b.date.localeCompare(a.date))
        : [],
    [session, prescriptions],
  );

  const myPayments = useMemo(
    () =>
      session && session.profile === "PATIENT"
        ? payments
            .filter((m: PaymentRecord) => m.patientId === session.patientId)
            .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        : [],
    [session, payments],
  );

  if (!session || session.profile !== "PATIENT") return null;

  const firstName = session.fullName.split(" ")[0];

  return (
    <div className="flex min-h-screen w-full flex-col bg-background">
      {/* Barre du portail */}
      <header
        role="banner"
        className="sticky top-0 z-40 border-b border-border/80 bg-card/90 backdrop-blur supports-[backdrop-filter]:bg-card/75"
      >
        <div className="mx-auto flex h-14 w-full max-w-5xl items-center justify-between gap-2 px-3 sm:px-6">
          <div className="flex min-w-0 items-center gap-2.5">
            <span
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-medical text-ink-medical shadow-tile"
              aria-hidden="true"
            >
              <HeartPulse className="h-5 w-5" />
            </span>
            <div className="min-w-0 leading-tight">
              <p className="truncate text-sm font-bold tracking-tight text-primary">
                PUBLIC HEALTH
              </p>
              <p className="hidden truncate text-xs text-muted-foreground sm:block">
                Espace patient · Burkina Faso
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1.5 sm:gap-3">
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={() => setTheme(isDark ? "light" : "dark")}
              title="Basculer clair/sombre"
              aria-label="Basculer le thème clair ou sombre"
            >
              {isDark ? (
                <Sun className="h-4 w-4" aria-hidden="true" />
              ) : (
                <Moon className="h-4 w-4" aria-hidden="true" />
              )}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="rounded-full"
              onClick={logout}
            >
              <LogOut className="h-4 w-4" aria-hidden="true" />
              <span className="hidden sm:inline">Se déconnecter</span>
              <span className="sm:hidden">Quitter</span>
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-5xl flex-1 space-y-5 px-3 py-5 sm:px-6">
        {/* Héro */}
        <motion.div {...sectionMotion(0)}>
          <div className="relative overflow-hidden rounded-3xl bg-gradient-medical shadow-float">
            <div className="absolute inset-0 bg-plus-pattern" aria-hidden="true" />
            <div
              className="absolute -right-14 -top-20 h-52 w-52 rounded-full bg-white/30 blur-2xl"
              aria-hidden="true"
            />
            <div className="relative flex flex-wrap items-start justify-between gap-3 p-5 sm:p-6">
              <div className="min-w-0">
                <p className="text-[11px] font-semibold uppercase tracking-widest text-ink-medical/70">
                  Espace patient
                </p>
                <h1 className="mt-1.5 text-2xl font-bold leading-tight tracking-tight text-ink-medical sm:text-3xl">
                  Bonjour {firstName}
                </h1>
                <p className="mt-1 text-sm font-medium text-ink-medical/80">
                  Suivez vos soins : ordonnances, dispensations et paiements.
                </p>
              </div>
              {me && (
                <div className="flex flex-col items-end gap-1.5">
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-white/75 px-3 py-1.5 text-xs font-semibold text-ink-medical backdrop-blur">
                    <MapPin className="h-3.5 w-3.5" aria-hidden="true" />
                    {me.facility}
                  </span>
                  <span className="tnum rounded-full bg-ink-medical/10 px-3 py-1 text-[11px] font-semibold text-ink-medical">
                    {me.phReference}
                  </span>
                </div>
              )}
            </div>
          </div>
        </motion.div>

        {/* Mes informations */}
        <motion.section {...sectionMotion(0.05)} aria-labelledby="mes-infos">
          <h2
            id="mes-infos"
            className="mb-2 flex items-center gap-2 px-1 text-sm font-semibold text-foreground"
          >
            <IdCard className="h-4 w-4 text-primary" aria-hidden="true" />
            Mes informations
          </h2>
          {me ? <IdentityCard patient={me} /> : <EmptyCard message="Dossier introuvable dans le miroir local — reconnectez-vous." />}
        </motion.section>

        {/* Mes ordonnances */}
        <motion.section {...sectionMotion(0.1)} aria-labelledby="mes-ordonnances">
          <h2
            id="mes-ordonnances"
            className="mb-2 flex items-center gap-2 px-1 text-sm font-semibold text-foreground"
          >
            <ClipboardList className="h-4 w-4 text-amber-600 dark:text-amber-400" aria-hidden="true" />
            Mes ordonnances
            <span className="tnum rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
              {myPrescriptions.length}
            </span>
          </h2>
          {myPrescriptions.length === 0 ? (
            <EmptyCard message="Aucune ordonnance enregistrée pour le moment." />
          ) : (
            <div className="space-y-3">
              {myPrescriptions.map((r) => (
                <PrescriptionCard key={r.id} prescription={r} />
              ))}
            </div>
          )}
        </motion.section>

        {/* Mes paiements */}
        <motion.section {...sectionMotion(0.15)} aria-labelledby="mes-paiements">
          <h2
            id="mes-paiements"
            className="mb-2 flex items-center gap-2 px-1 text-sm font-semibold text-foreground"
          >
            <Wallet className="h-4 w-4 text-teal-600 dark:text-teal-400" aria-hidden="true" />
            Mes paiements
            <span className="tnum rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground">
              {myPayments.length}
            </span>
          </h2>
          {myPayments.length === 0 ? (
            <EmptyCard message="Aucun paiement enregistré pour le moment." />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              {myPayments.map((m) => (
                <PaymentCard key={m.id} payment={m} />
              ))}
            </div>
          )}
        </motion.section>
      </main>

      <footer className="mt-auto border-t border-border bg-card">
        <div className="mx-auto flex max-w-5xl flex-col items-start justify-between gap-2 px-4 py-4 text-xs text-muted-foreground sm:flex-row sm:items-center sm:px-6">
          <p>PUBLIC HEALTH · Espace patient · Patient d&apos;abord</p>
          <p>Mode démonstration — vos données réelles ne quittent jamais le poste de santé.</p>
        </div>
      </footer>
    </div>
  );
}

/* -------------------------------------------------------------------- */
/* Cartes                                                               */
/* -------------------------------------------------------------------- */

function IdentityCard({ patient }: { patient: Patient }) {
  const rows: { label: string; value: string }[] = [
    { label: "Nom complet", value: `${patient.name.given} ${patient.name.family}` },
    { label: "Naissance", value: `${formatDate(patient.birthDate)} · ${ageFrom(patient.birthDate)} ans` },
    { label: "Sexe", value: patient.gender === "F" ? "Féminin" : "Masculin" },
    { label: "Téléphone", value: patient.phone ? `+226 ${patient.phone.replace(/(\d{2})(?=\d)/g, "$1 ").trim()}` : "—" },
    { label: "Village / quartier", value: patient.village ?? "—" },
    { label: "Structure de rattachement", value: patient.facility },
    ...patient.identifiers.map((id) => ({
      label: id.type === "NUNP" ? "NUNP (identifiant national)" : "CNIB",
      value: id.value,
    })),
  ];

  return (
    <div className="rounded-3xl border border-border bg-card p-5 shadow-tile">
      <div className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
        {rows.map((row) => (
          <div key={row.label} className="flex items-baseline justify-between gap-3 border-b border-dashed border-border/70 pb-2 last:border-0 sm:last:border-0">
            <span className="text-xs text-muted-foreground">{row.label}</span>
            <span className="text-right text-sm font-medium text-foreground">{row.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function PrescriptionCard({ prescription }: { prescription: Prescription }) {
  const adv = advancement(prescription);
  return (
    <div className="rounded-3xl border border-border bg-card p-5 shadow-tile">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-foreground">
            {prescription.diagnosis}
          </p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1">
              <CalendarDays className="h-3.5 w-3.5" aria-hidden="true" />
              {formatDate(prescription.date)}
            </span>
            <span aria-hidden="true">·</span>
            <span>{prescription.prescriber}</span>
          </p>
        </div>
        <Badge variant="outline" className={STATUS_BADGE_CLASS[prescription.status]}>
          {STATUS_LABELS[prescription.status]}
        </Badge>
      </div>

      {/* Lignes prescrites + reste à récupérer */}
      <ul className="mt-4 space-y-2">
        {prescription.items.map((item) => {
          const rest = remainingOf(prescription, item.drug, item.quantity);
          return (
            <li
              key={item.drug}
              className="flex items-center justify-between gap-3 rounded-2xl bg-muted/60 px-3.5 py-2.5"
            >
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 truncate text-sm font-medium text-foreground">
                  <Pill className="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
                  {item.drug}
                </p>
                <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                  {item.dosage} · {item.frequency} · {item.durationDays} jours
                </p>
              </div>
              <span
                className={`tnum shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ${
                  rest > 0
                    ? "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                    : "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                }`}
              >
                {rest > 0 ? `reste ${rest} / ${item.quantity}` : "complet"}
              </span>
            </li>
          );
        })}
      </ul>

      {/* Avancement global */}
      <div className="mt-4">
        <div
          className="flex items-center justify-between text-[11px] text-muted-foreground"
          aria-label={`Dispensation : ${adv.done} sur ${adv.total} unités`}
        >
          <span>Dispensation à la pharmacie</span>
          <span className="tnum font-semibold">
            {adv.done} / {adv.total}
          </span>
        </div>
        <div
          role="progressbar"
          aria-valuenow={Math.round(adv.ratio * 100)}
          aria-valuemin={0}
          aria-valuemax={100}
          className="mt-1.5 h-2 overflow-hidden rounded-full bg-muted"
        >
          <div
            className="h-full rounded-full bg-gradient-medical transition-all"
            style={{ width: `${Math.round(adv.ratio * 100)}%` }}
          />
        </div>
      </div>
    </div>
  );
}

function PaymentCard({ payment }: { payment: PaymentRecord }) {
  const channel = CHANNEL_CONFIG[payment.channel];
  const ChannelIcon = channel.icon;
  return (
    <div className="rounded-3xl border border-border bg-card p-5 shadow-tile">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
            <Banknote className="h-4 w-4 shrink-0 text-teal-600 dark:text-teal-400" aria-hidden="true" />
            {payment.purpose}
          </p>
          <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1">
              <ChannelIcon className="h-3.5 w-3.5" aria-hidden="true" />
              {channel.short}
            </span>
            <span aria-hidden="true">·</span>
            <span className="tnum">{formatDate(payment.createdAt)}</span>
          </p>
        </div>
        <Badge variant="outline" className={STATE_BADGE_CLASS[payment.state]}>
          {STATE_LABELS[payment.state]}
        </Badge>
      </div>
      <p className="tnum mt-3 text-xl font-bold tracking-tight text-foreground">
        {formatXof(payment.amountXof)}
      </p>
    </div>
  );
}

function EmptyCard({ message }: { message: string }) {
  return (
    <div className="rounded-3xl border border-dashed border-border bg-card/60 p-6 text-center">
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  );
}
