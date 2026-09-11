"use client";

/**
 * Module Auth — écran de connexion multi-profils (démonstration).
 *
 * Deux familles de profils, comme dans l'architecture cible :
 *  - Patient : téléphone + code SMS à usage unique (OTP démo affiché) ;
 *  - Personnel de santé : identité + rôle + structure de rattachement.
 *
 * L'écran porte aussi le schéma du parcours de soins : qui fait quoi, dans
 * quel ordre, et comment les professionnels interagissent autour du même
 * dossier patient (MPI partagé + synchronisation).
 */

import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  ArrowLeft,
  ArrowRight,
  BadgeCheck,
  ChevronRight,
  ClipboardList,
  CircleAlert,
  HeartPulse,
  MessageSquare,
  Pill,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Stethoscope,
  UserRound,
  Users,
  Wallet,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useToast } from "@/hooks/use-toast";
import { FACILITY_NAMES, STAFF_ROLE_LABELS } from "@/lib/demo/reference";
import { useSessionStore } from "@/lib/session";
import { useAppStore } from "@/lib/store";
import type { StaffRole } from "@/lib/types";
import { cn } from "@/lib/utils";

/* -------------------------------------------------------------------- */
/* Parcours de soins — « qui interagit avec qui »                       */
/* -------------------------------------------------------------------- */

const PATHWAY: {
  icon: LucideIcon;
  title: string;
  who: string;
}[] = [
  { icon: Smartphone, title: "Le patient s'identifie", who: "Téléphone + code SMS" },
  { icon: Users, title: "Enregistrement / MPI", who: "Agent de saisie" },
  { icon: Stethoscope, title: "Consultation", who: "Infirmier · Médecin" },
  { icon: ClipboardList, title: "Ordonnance", who: "Médecin prescripteur" },
  { icon: Pill, title: "Dispensation", who: "Pharmacien" },
  { icon: Wallet, title: "Paiement", who: "Caissier · FedaPay" },
];

function PathwayDiagram() {
  return (
    <div className="rounded-3xl border border-white/15 bg-white/10 p-4 backdrop-blur">
      <p className="mb-3 text-[11px] font-semibold uppercase tracking-widest text-white/70">
        Le parcours de soins, un seul dossier
      </p>
      <ol className="space-y-2.5">
        {PATHWAY.map((step, index) => (
          <li key={step.title} className="flex items-center gap-3">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white/90 text-ink-medical shadow-tile">
              <step.icon className="h-4 w-4" aria-hidden="true" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-semibold text-white">
                <span className="tnum mr-1.5 text-white/50">{index + 1}.</span>
                {step.title}
              </p>
              <p className="truncate text-[11px] text-white/70">{step.who}</p>
            </div>
            {index < PATHWAY.length - 1 && (
              <ChevronRight
                className="h-3.5 w-3.5 shrink-0 text-white/40"
                aria-hidden="true"
              />
            )}
          </li>
        ))}
      </ol>
      <p className="mt-3 flex items-start gap-2 rounded-2xl bg-white/10 p-2.5 text-[11px] leading-snug text-white/80">
        <RefreshCw className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        Tout se synchronise via le miroir local (offline-first) et remonte au
        PH HUB — chaque professionnel travaille sur le même dossier, même sans
        réseau.
      </p>
    </div>
  );
}

/* -------------------------------------------------------------------- */
/* Comptes de démonstration                                             */
/* -------------------------------------------------------------------- */

const DEMO_PATIENTS: { name: string; phone: string }[] = [
  { name: "Aïcha WÉDRAOGO", phone: "70123456" },
  { name: "Salamata TRAORÉ", phone: "66884422" },
  { name: "Mariam OUATTARA", phone: "74112233" },
];

const DEMO_STAFF: { name: string; role: StaffRole; facility: string }[] = [
  { name: "Aminata Sawadogo", role: "INFIRMIER", facility: "CSPS Ouaga 12" },
  { name: "Jean Kiendrebeogo", role: "MEDECIN", facility: "CSPS Ouaga 12" },
  { name: "Estelle Sanou", role: "PHARMACIEN", facility: "CMA Kossodo" },
  { name: "Sylvie Bationo", role: "CAISSIER", facility: "CMA Kossodo" },
  { name: "Roger Compaore", role: "ADMIN", facility: "DRS Centre" },
];

function formatPhone(phone: string): string {
  return phone.replace(/(\d{2})(?=\d)/g, "$1 ").trim();
}

function normalizePhone(value: string): string {
  return value.replace(/\D/g, "");
}

/* -------------------------------------------------------------------- */
/* Écran de connexion                                                   */
/* -------------------------------------------------------------------- */

type Step = "profile" | "patient-phone" | "patient-otp" | "staff-form";

export function LoginView() {
  const loginPatient = useSessionStore((s) => s.loginPatient);
  const loginStaff = useSessionStore((s) => s.loginStaff);
  const patients = useAppStore((s) => s.patients);
  const { toast } = useToast();

  const [step, setStep] = useState<Step>("profile");
  const [phone, setPhone] = useState("");
  const [otp, setOtp] = useState("");
  const [expectedOtp, setExpectedOtp] = useState<string | null>(null);
  const [pendingPatientId, setPendingPatientId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [staffName, setStaffName] = useState("");
  const [staffRole, setStaffRole] = useState<StaffRole>("INFIRMIER");
  const [staffFacility, setStaffFacility] = useState(FACILITY_NAMES[0]);

  const pendingPatient = useMemo(
    () => patients.find((p) => p.id === pendingPatientId) ?? null,
    [patients, pendingPatientId],
  );

  function sendCode() {
    const digits = normalizePhone(phone);
    if (digits.length < 8) {
      setError("Numéro incomplet : 8 chiffres attendus (ex. 70 12 34 56).");
      return;
    }
    const match = patients.find(
      (p) => p.active && normalizePhone(p.phone ?? "") === digits,
    );
    if (!match) {
      setError(
        "Aucun dossier actif avec ce numéro dans le miroir. Essayez un compte de démonstration ci-dessous.",
      );
      return;
    }
    const code = String(Math.floor(1000 + Math.random() * 9000));
    setExpectedOtp(code);
    setPendingPatientId(match.id);
    setError(null);
    setStep("patient-otp");
    toast({
      title: "Code SMS envoyé (démo)",
      description: `Vers +226 ${formatPhone(digits)} · code : ${code}`,
    });
  }

  function verifyOtp() {
    if (otp.trim() !== expectedOtp) {
      setError("Code incorrect — vérifiez le code affiché dans le SMS de démo.");
      return;
    }
    if (!pendingPatient) return;
    loginPatient({
      patientId: pendingPatient.id,
      fullName: `${pendingPatient.name.given} ${pendingPatient.name.family}`,
      phone: normalizePhone(pendingPatient.phone ?? phone),
      facility: pendingPatient.facility,
    });
  }

  function submitStaff() {
    if (staffName.trim().length < 3) {
      setError("Indiquez votre nom complet (au moins 3 caractères).");
      return;
    }
    loginStaff({ fullName: staffName.trim(), role: staffRole, facility: staffFacility });
  }

  return (
    <div className="grid min-h-screen w-full lg:grid-cols-[1.05fr_1fr]">
      {/* Panneau identité — bleu nuit, croix médicales en filigrane */}
      <div className="relative hidden overflow-hidden bg-gradient-medical-deep p-8 lg:flex lg:flex-col lg:justify-between xl:p-12">
        <div className="absolute inset-0 bg-plus-pattern opacity-60" aria-hidden="true" />
        <div
          className="absolute -right-24 -top-24 h-72 w-72 rounded-full bg-emerald-400/20 blur-3xl"
          aria-hidden="true"
        />
        <div
          className="absolute -bottom-32 -left-16 h-80 w-80 rounded-full bg-teal-300/15 blur-3xl"
          aria-hidden="true"
        />

        <div className="relative">
          <div className="flex items-center gap-3">
            <span
              className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-medical text-ink-medical shadow-float"
              aria-hidden="true"
            >
              <HeartPulse className="h-6 w-6" />
            </span>
            <div className="leading-tight">
              <p className="text-lg font-bold tracking-tight text-white">
                PUBLIC HEALTH
              </p>
              <p className="text-xs text-white/70">
                Burkina Faso · Plateforme nationale
              </p>
            </div>
          </div>

          <h1 className="mt-10 max-w-md text-3xl font-bold leading-tight tracking-tight text-white xl:text-4xl">
            Votre santé, notre priorité.
          </h1>
          <p className="mt-3 max-w-md text-sm leading-relaxed text-white/75">
            Un seul dossier patient pour tout le parcours : identification,
            consultation, ordonnance, dispensation, paiement — en ligne comme
            hors ligne.
          </p>
        </div>

        <div className="relative mt-8 max-w-md">
          <PathwayDiagram />
        </div>

        <p className="relative mt-8 text-[11px] leading-relaxed text-white/50">
          Mode démonstration · données fictives · en production, les comptes
          sont gérés par le module sécurité du backend (OIDC, MFA pour les rôles
          sensibles).
        </p>
      </div>

      {/* Panneau de connexion */}
      <div className="flex flex-col justify-center bg-background px-4 py-10 sm:px-8 lg:px-12">
        <div className="mx-auto w-full max-w-md">
          {/* En-tête compact mobile */}
          <div className="mb-8 flex items-center gap-3 lg:hidden">
            <span
              className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-medical text-ink-medical shadow-tile"
              aria-hidden="true"
            >
              <HeartPulse className="h-5 w-5" />
            </span>
            <div className="leading-tight">
              <p className="text-sm font-bold tracking-tight text-primary">
                PUBLIC HEALTH
              </p>
              <p className="text-xs text-muted-foreground">
                Plateforme nationale de santé
              </p>
            </div>
          </div>

          <AnimatePresence mode="wait">
            {step === "profile" && (
              <motion.div
                key="profile"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.2 }}
              >
                <h2 className="text-2xl font-bold tracking-tight">
                  Qui êtes-vous ?
                </h2>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  Choisissez votre profil pour accéder à votre espace.
                </p>

                <div className="mt-6 space-y-3">
                  <button
                    type="button"
                    onClick={() => {
                      setError(null);
                      setStep("patient-phone");
                    }}
                    className="group flex w-full items-center gap-4 rounded-3xl border border-border bg-card p-5 text-left transition-all hover:border-primary/40 hover:shadow-tile focus-visible:outline-2 focus-visible:outline-ring"
                  >
                    <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                      <UserRound className="h-6 w-6" aria-hidden="true" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-base font-semibold">
                        Patient
                      </span>
                      <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">
                        J&apos;ai un dossier dans une structure de santé — je
                        me connecte avec mon numéro de téléphone.
                      </span>
                    </span>
                    <ArrowRight
                      className="h-5 w-5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                      aria-hidden="true"
                    />
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setError(null);
                      setStep("staff-form");
                    }}
                    className="group flex w-full items-center gap-4 rounded-3xl border border-border bg-card p-5 text-left transition-all hover:border-primary/40 hover:shadow-tile focus-visible:outline-2 focus-visible:outline-ring"
                  >
                    <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                      <Stethoscope className="h-6 w-6" aria-hidden="true" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block text-base font-semibold">
                        Professionnel de santé
                      </span>
                      <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">
                        Agent MPI, infirmier, médecin, pharmacien, caissier,
                        superviseur ou administrateur.
                      </span>
                    </span>
                    <ArrowRight
                      className="h-5 w-5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-primary"
                      aria-hidden="true"
                    />
                  </button>
                </div>

                <p className="mt-6 flex items-start gap-2 text-[11px] leading-relaxed text-muted-foreground">
                  <ShieldCheck
                    className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-600 dark:text-emerald-400"
                    aria-hidden="true"
                  />
                  Chaque rôle ne voit que son périmètre : la navigation
                  s&apos;adapte automatiquement après connexion.
                </p>
              </motion.div>
            )}

            {step === "patient-phone" && (
              <motion.div
                key="patient-phone"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.2 }}
              >
                <BackButton onBack={() => setStep("profile")} />
                <h2 className="text-2xl font-bold tracking-tight">
                  Espace patient
                </h2>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  Saisissez le numéro de téléphone lié à votre dossier.
                </p>

                <div className="mt-6 space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="phone">Numéro de téléphone</Label>
                    <div className="flex gap-2">
                      <span className="flex h-10 items-center rounded-full border border-border bg-muted px-4 text-sm font-medium text-muted-foreground">
                        +226
                      </span>
                      <Input
                        id="phone"
                        inputMode="numeric"
                        autoComplete="tel-national"
                        placeholder="70 12 34 56"
                        value={formatPhone(phone)}
                        onChange={(e) => setPhone(e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && sendCode()}
                        className="rounded-full"
                      />
                    </div>
                  </div>

                  <Button
                    variant="medical"
                    size="lg"
                    className="w-full"
                    onClick={sendCode}
                  >
                    <MessageSquare className="h-4 w-4" aria-hidden="true" />
                    Recevoir mon code par SMS
                  </Button>
                </div>

                <DemoChips
                  title="Comptes patients de démonstration"
                  items={DEMO_PATIENTS.map((p) => ({
                    key: p.phone,
                    label: `${p.name} · ${formatPhone(p.phone)}`,
                    onClick: () => {
                      setPhone(p.phone);
                      setError(null);
                    },
                  }))}
                />
                {error && <ErrorBox message={error} />}
              </motion.div>
            )}

            {step === "patient-otp" && (
              <motion.div
                key="patient-otp"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.2 }}
              >
                <BackButton onBack={() => setStep("patient-phone")} />
                <h2 className="text-2xl font-bold tracking-tight">
                  Code de vérification
                </h2>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  {pendingPatient
                    ? `Dossier trouvé : ${pendingPatient.name.given} ${pendingPatient.name.family} (${pendingPatient.phReference}). Code envoyé au +226 ${formatPhone(normalizePhone(pendingPatient.phone ?? phone))}.`
                    : "Code envoyé par SMS."}
                </p>

                <div className="mt-5 flex items-start gap-2.5 rounded-2xl border border-emerald-500/30 bg-emerald-500/10 p-3.5 text-xs leading-relaxed text-emerald-800 dark:text-emerald-300">
                  <MessageSquare
                    className="mt-0.5 h-4 w-4 shrink-0"
                    aria-hidden="true"
                  />
                  <span>
                    <strong className="font-semibold">SMS de démonstration</strong>{" "}
                    — votre code à usage unique est{" "}
                    <strong className="tnum font-bold">{expectedOtp}</strong>.
                    <br />
                    <span className="text-emerald-700/80 dark:text-emerald-400/80">
                      En production, ce code arrive par SMS réel et n&apos;est
                      jamais affiché à l&apos;écran.
                    </span>
                  </span>
                </div>

                <div className="mt-4 space-y-2">
                  <Label htmlFor="otp">Code à 4 chiffres</Label>
                  <Input
                    id="otp"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    placeholder="••••"
                    maxLength={4}
                    value={otp}
                    onChange={(e) => setOtp(e.target.value.replace(/\D/g, ""))}
                    onKeyDown={(e) => e.key === "Enter" && verifyOtp()}
                    className="tnum rounded-full text-center text-lg tracking-[0.4em]"
                  />
                </div>

                <Button
                  variant="medical"
                  size="lg"
                  className="mt-4 w-full"
                  onClick={verifyOtp}
                >
                  <BadgeCheck className="h-4 w-4" aria-hidden="true" />
                  Vérifier et ouvrir mon espace
                </Button>
                {error && <ErrorBox message={error} />}
              </motion.div>
            )}

            {step === "staff-form" && (
              <motion.div
                key="staff-form"
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.2 }}
              >
                <BackButton onBack={() => setStep("profile")} />
                <h2 className="text-2xl font-bold tracking-tight">
                  Poste de santé
                </h2>
                <p className="mt-1.5 text-sm text-muted-foreground">
                  Identifiez-vous pour ouvrir votre poste de travail.
                </p>

                <div className="mt-6 space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="staff-name">Nom complet</Label>
                    <Input
                      id="staff-name"
                      autoComplete="name"
                      placeholder="Ex. Aminata Sawadogo"
                      value={staffName}
                      onChange={(e) => setStaffName(e.target.value)}
                      onKeyDown={(e) => e.key === "Enter" && submitStaff()}
                      className="rounded-full"
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="staff-role">Rôle</Label>
                    <Select
                      value={staffRole}
                      onValueChange={(v) => setStaffRole(v as StaffRole)}
                    >
                      <SelectTrigger id="staff-role" className="rounded-full">
                        <SelectValue placeholder="Choisir un rôle" />
                      </SelectTrigger>
                      <SelectContent>
                        {(Object.keys(STAFF_ROLE_LABELS) as StaffRole[]).map(
                          (role) => (
                            <SelectItem key={role} value={role}>
                              {STAFF_ROLE_LABELS[role]}
                            </SelectItem>
                          ),
                        )}
                      </SelectContent>
                    </Select>
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="staff-facility">Structure</Label>
                    <Select
                      value={staffFacility}
                      onValueChange={setStaffFacility}
                    >
                      <SelectTrigger id="staff-facility" className="rounded-full">
                        <SelectValue placeholder="Choisir une structure" />
                      </SelectTrigger>
                      <SelectContent>
                        {FACILITY_NAMES.map((name) => (
                          <SelectItem key={name} value={name}>
                            {name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  <Button
                    variant="medical"
                    size="lg"
                    className="w-full"
                    onClick={submitStaff}
                  >
                    <HeartPulse className="h-4 w-4" aria-hidden="true" />
                    Ouvrir mon poste
                  </Button>
                </div>

                <DemoChips
                  title="Comptes de démonstration (clic = pré-remplissage)"
                  items={DEMO_STAFF.map((s) => ({
                    key: s.name,
                    label: `${s.name} · ${STAFF_ROLE_LABELS[s.role].split(" (")[0]} · ${s.facility}`,
                    onClick: () => {
                      setStaffName(s.name);
                      setStaffRole(s.role);
                      setStaffFacility(s.facility);
                      setError(null);
                    },
                  }))}
                />
                <p className="mt-4 text-[11px] leading-relaxed text-muted-foreground">
                  Démonstration : cette connexion simulée ne porte aucune
                  sécurité réelle. En production, chaque poste s&apos;authentifie
                  par compte personnel (JWT, MFA pour les rôles sensibles) et
                  le serveur applique la matrice des rôles — visible dans le
                  back-office, onglet « Rôles et permissions ».
                </p>
                {error && <ErrorBox message={error} />}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------- */
/* Sous-composants                                                      */
/* -------------------------------------------------------------------- */

function BackButton({ onBack }: { onBack: () => void }) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="mb-4 -ml-2 rounded-full text-muted-foreground"
      onClick={onBack}
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      Retour
    </Button>
  );
}

function ErrorBox({ message }: { message: string }) {
  return (
    <p
      role="alert"
      className="mt-4 flex items-start gap-2 rounded-2xl border border-destructive/30 bg-destructive/10 p-3 text-xs leading-relaxed text-destructive"
    >
      <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      {message}
    </p>
  );
}

function DemoChips({
  title,
  items,
}: {
  title: string;
  items: { key: string; label: string; onClick: () => void }[];
}) {
  return (
    <div className="mt-6">
      <p className="text-[11px] font-semibold uppercase tracking-widest text-muted-foreground/80">
        {title}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        {items.map((item) => (
          <button
            key={item.key}
            type="button"
            onClick={item.onClick}
            className={cn(
              "rounded-full border border-border bg-card px-3 py-1.5 text-xs font-medium text-foreground/80",
              "transition-all hover:border-primary/40 hover:text-foreground active:scale-[0.97]",
            )}
          >
            {item.label}
          </button>
        ))}
      </div>
    </div>
  );
}
