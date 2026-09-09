"use client";

import {
  Activity,
  BadgeCheck,
  Boxes,
  CheckCircle2,
  Database,
  FileCheck2,
  GitBranch,
  HeartPulse,
  Layers,
  ScrollText,
  ShieldCheck,
  Smartphone,
  TestTube2,
} from "lucide-react";
import { AppHeader } from "@/components/app-header";
import { PaymentStepper } from "@/components/payment-stepper";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/hooks/use-toast";

const SPRINT0_DONE = [
  "Monorepo échafaudé : apps/pwa + services/api",
  "API Java 21 + Spring Boot 3, squelette 15 modules",
  "Migrations Flyway V1→V5 (identity, payments, clinical, sync, RLS)",
  "Tranche verticale paiements : 8 états + webhooks HMAC + idempotence",
  "CI GitHub Actions : build + tests (Testcontainers)",
  "Design system v0.1 : tokens, shadcn/ui, Lucide + simple-icons",
  "PWA : manifeste, service worker, repli hors ligne",
  "12 ADR formalisés + docs backlog et hypothèses",
];

const EPICS = [
  {
    id: "E1",
    title: "Identité & MPI",
    scenario: "Patient dupliqué",
    detail:
      "Recherche miroir local, création avec détection de doublons (409), file de revue, fusion tracée.",
  },
  {
    id: "E2",
    title: "Consultation offline + sync",
    scenario: "Consultation hors ligne, reprise réseau",
    detail:
      "UUID v7 côté client, append-only, outbox IndexedDB, /sync/batch idempotent, 3 régimes de conflit.",
  },
  {
    id: "E3",
    title: "Ordonnance & dispensation",
    scenario: "Dispensation multi-sources",
    detail:
      "Ordonnance PDF, dispensation interne, connecteur HUB simulé (enveloppe HMAC signée).",
  },
  {
    id: "E4",
    title: "Paiements FedaPay",
    scenario: "Webhook dupliqué, paiement réseau coupé",
    detail:
      "8 états forward-only, webhooks signés et idempotents, réconciliation nocturne.",
  },
  {
    id: "E5",
    title: "Audit & sécurité",
    scenario: "Accès d'urgence, accès hors périmètre",
    detail:
      "Audit six dimensions chaîné, RLS complète, break-the-glass, revue OWASP ASVS L2.",
  },
  {
    id: "E6",
    title: "Back-office minimal",
    scenario: "Gouvernance de base",
    detail: "Utilisateurs, structures sanitaires, rôles, MFA administrateur.",
  },
  {
    id: "E7",
    title: "Façade FHIR R4",
    scenario: "Interopérabilité sortante",
    detail: "19 ressources en lecture/recherche, validateur HAPI dans la CI.",
  },
  {
    id: "E8",
    title: "Observabilité & durcissement",
    scenario: "SLO, restauration testée",
    detail: "Dashboards SLO, alertes, sauvegarde 3-2-1 avec exercice de restauration.",
  },
];

const STACK = [
  { slug: "spring", label: "Spring Boot 3" },
  { slug: "openjdk", label: "Java 21" },
  { slug: "postgresql", label: "PostgreSQL" },
  { slug: "flyway", label: "Flyway" },
  { slug: "hibernate", label: "Hibernate" },
  { slug: "nextdotjs", label: "Next.js 16" },
  { slug: "typescript", label: "TypeScript" },
  { slug: "tailwindcss", label: "Tailwind 4" },
  { slug: "docker", label: "Docker" },
  { slug: "kubernetes", label: "K8s (réservé)" },
  { slug: "keycloak", label: "Keycloak (réservé)" },
  { slug: "sentry", label: "Sentry" },
  { slug: "posthog", label: "PostHog (non sensible)" },
  { slug: "opentelemetry", label: "OpenTelemetry" },
  { slug: "swagger", label: "OpenAPI" },
  { slug: "githubactions", label: "GitHub Actions" },
];

const TOKENS = [
  { name: "Primaire teal", hex: "#0F766E", className: "bg-[#0F766E]" },
  { name: "Hover", hex: "#115E59", className: "bg-[#115E59]" },
  { name: "Alerte", hex: "#D97706", className: "bg-[#D97706]" },
  { name: "Erreur", hex: "#DC2626", className: "bg-[#DC2626]" },
  { name: "Fond", hex: "#F8FAFC", className: "bg-[#F8FAFC] border border-border" },
  { name: "Texte", hex: "#0F172A", className: "bg-[#0F172A]" },
];

const KPIS = [
  { label: "Modules métier", value: "15", icon: Boxes },
  { label: "Migrations Flyway", value: "5", icon: Database },
  { label: "ADR formalisés", value: "12", icon: ScrollText },
  { label: "Épiques P0", value: "8", icon: Layers },
];

export default function Home() {
  const { toast } = useToast();

  const notifySync = () => {
    toast({
      title: "Synchronisation vérifiée",
      description:
        "Aucune opération en attente. La file d'attente et le protocole complet arrivent avec l'épique E2.",
    });
  };

  return (
    <div className="min-h-screen w-full">
      <AppHeader queuedOperations={0} />

      <main className="mx-auto w-full max-w-6xl flex-1 space-y-6 px-4 py-8 sm:px-6">
        {/* HERO */}
        <section className="space-y-3" aria-labelledby="sprint-title">
          <div className="flex flex-wrap items-center gap-2">
            <Badge className="gap-1.5 bg-primary text-primary-foreground">
              <Activity className="h-3.5 w-3.5" aria-hidden="true" />
              Sprint 0 — livré
            </Badge>
            <Badge variant="outline" className="gap-1.5">
              <GitBranch className="h-3.5 w-3.5" aria-hidden="true" />
              Monorepo public-health
            </Badge>
            <Badge variant="outline" className="gap-1.5">
              <Smartphone className="h-3.5 w-3.5" aria-hidden="true" />
              PWA offline-first
            </Badge>
          </div>
          <h1
            id="sprint-title"
            className="text-2xl font-semibold tracking-tight sm:text-3xl"
          >
            PUBLIC HEALTH — socle opérationnel en place
          </h1>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground sm:text-base">
            Plateforme nationale d'interopérabilité et de services de santé du
            Burkina Faso. Trois piliers : <strong className="text-foreground">ID</strong>{" "}
            (identité), <strong className="text-foreground">HUB</strong>{" "}
            (interopérabilité), <strong className="text-foreground">DATA</strong>{" "}
            (données). Monolithe modulaire, FHIR R4 en façade, offline-first
            non négociable, patient d'abord.
          </p>
        </section>

        {/* KPI */}
        <section
          className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4"
          aria-label="Indicateurs Sprint 0"
        >
          {KPIS.map(({ label, value, icon: Icon }) => (
            <Card key={label} className="py-4">
              <CardContent className="flex items-center gap-3 px-4">
                <span
                  className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
                  aria-hidden="true"
                >
                  <Icon className="h-5 w-5" />
                </span>
                <div className="min-w-0">
                  <p className="text-xl font-semibold tabular-nums leading-none">
                    {value}
                  </p>
                  <p className="mt-1 truncate text-xs text-muted-foreground">
                    {label}
                  </p>
                </div>
              </CardContent>
            </Card>
          ))}
        </section>

        {/* VERIFICATIONS + PAIEMENT */}
        <section className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <FileCheck2 className="h-4 w-4 text-primary" aria-hidden="true" />
                Vérifications Sprint 0
              </CardTitle>
              <CardDescription>
                Fondations posées avant toute fonctionnalité métier (conforme à
                la feuille de route en 9 phases).
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="space-y-2.5">
                {SPRINT0_DONE.map((item) => (
                  <li key={item} className="flex items-start gap-2.5 text-sm">
                    <CheckCircle2
                      className="mt-0.5 h-4 w-4 shrink-0 text-primary"
                      aria-hidden="true"
                    />
                    <span className="leading-relaxed">{item}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <BadgeCheck className="h-4 w-4 text-primary" aria-hidden="true" />
                Machine à états du paiement
              </CardTitle>
              <CardDescription>
                L'argent ne pardonne rien : transitions forward-only, la
                réconciliation nocturne fait foi.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <PaymentStepper current="INITIATED" />
              <Separator />
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>Progression des épiques P0</span>
                  <span className="tabular-nums">0 % — backlog prêt</span>
                </div>
                <Progress value={0} aria-label="Progression backlog P0" />
              </div>
              <Button onClick={notifySync} className="w-full sm:w-auto">
                Vérifier la synchronisation
              </Button>
            </CardContent>
          </Card>
        </section>

        {/* BACKLOG P0 */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Layers className="h-4 w-4 text-primary" aria-hidden="true" />
              Backlog P0 — 8 épiques, 10 scénarios critiques en porte de sortie
            </CardTitle>
            <CardDescription>
              Ordre de construction et scénario critique couvert par chaque
              épique. Chaque user story suit la chaîne complète : besoin →
              tests → sécurité → observabilité → documentation.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="grid gap-3 sm:grid-cols-2">
              {EPICS.map((epic) => (
                <li
                  key={epic.id}
                  className="rounded-lg border border-border bg-muted/30 p-3.5"
                >
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-sm font-medium">
                      <span className="text-primary">{epic.id}</span> —{" "}
                      {epic.title}
                    </p>
                    <Badge
                      variant="secondary"
                      className="shrink-0 text-[10px] font-normal"
                    >
                      Backlog
                    </Badge>
                  </div>
                  <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
                    {epic.detail}
                  </p>
                  <p className="mt-2 inline-flex items-center gap-1.5 text-[11px] font-medium text-amber-700">
                    <TestTube2 className="h-3 w-3" aria-hidden="true" />
                    {epic.scenario}
                  </p>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        {/* STACK + TOKENS */}
        <section className="grid gap-4 lg:grid-cols-5">
          <Card className="lg:col-span-3">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Boxes className="h-4 w-4 text-primary" aria-hidden="true" />
                Stack technique — icônes de marques (CC0)
              </CardTitle>
              <CardDescription>
                Lucide pour l'interface, simple-icons pour les marques
                (ADR-012). K8s et Keycloak sont des réservations de migration,
                pas des dépendances du MVP.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="grid grid-cols-4 gap-2 sm:grid-cols-8">
                {STACK.map(({ slug, label }) => (
                  <li
                    key={slug}
                    className="flex flex-col items-center gap-1.5 rounded-lg border border-border bg-card p-2"
                    title={label}
                  >
                    {/* simple-icons : marques CC0, src local */}
                    <img
                      src={`/brands/${slug}.svg`}
                      alt=""
                      width={24}
                      height={24}
                      className="h-6 w-6"
                      loading="lazy"
                    />
                    <span className="truncate text-[10px] text-muted-foreground">
                      {label}
                    </span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>

          <Card className="lg:col-span-2">
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <HeartPulse className="h-4 w-4 text-primary" aria-hidden="true" />
                Design tokens v0.1
              </CardTitle>
              <CardDescription>
                Typographie Inter, chiffres tabulaires pour les montants XOF,
                rayons 8 px, cibles tactiles ≥ 48 px.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ul className="grid grid-cols-2 gap-2.5">
                {TOKENS.map((token) => (
                  <li
                    key={token.name}
                    className="flex items-center gap-2.5 rounded-lg border border-border p-2"
                  >
                    <span
                      className={`h-8 w-8 shrink-0 rounded-md ${token.className}`}
                      aria-hidden="true"
                    />
                    <div className="min-w-0 leading-tight">
                      <p className="truncate text-xs font-medium">
                        {token.name}
                      </p>
                      <p className="font-mono text-[10px] text-muted-foreground">
                        {token.hex}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        </section>

        {/* SECURITE */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <ShieldCheck className="h-4 w-4 text-primary" aria-hidden="true" />
              Défense en profondeur — 4 plans
            </CardTitle>
            <CardDescription>
              Sans Cloudflare (décision du porteur, ADR-011) : la protection
              applicative et le RLS portent le poids de la sécurité.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {[
                {
                  plan: "Plan 1 — Accès",
                  text: "TLS Vercel/Render, limites de taille, en-têtes de sécurité stricts",
                },
                {
                  plan: "Plan 2 — Application",
                  text: "AuthN JWT, RBAC × ABAC, validation d'entrée, Bucket4j",
                },
                {
                  plan: "Plan 3 — Données",
                  text: "RLS sur chaque table sensible, la base refuse en dernier recours",
                },
                {
                  plan: "Plan 4 — Audit",
                  text: "Six dimensions, append-only, chaînage par hachage",
                },
              ].map(({ plan, text }) => (
                <li key={plan} className="rounded-lg border bg-muted/30 p-3">
                  <p className="text-xs font-semibold text-primary">{plan}</p>
                  <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {text}
                  </p>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      </main>

      <footer className="mt-auto border-t border-border bg-card">
        <div className="mx-auto flex max-w-6xl flex-col items-start justify-between gap-2 px-4 py-5 text-xs text-muted-foreground sm:flex-row sm:items-center sm:px-6">
          <p>
            PUBLIC HEALTH v0.1 — Sprint 0 · ID / HUB / DATA · Patient d'abord
          </p>
          <p>Données de santé : jamais dans les analytics · OWASP ASVS L2 visé</p>
        </div>
      </footer>
    </div>
  );
}
