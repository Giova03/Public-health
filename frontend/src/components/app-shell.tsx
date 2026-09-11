"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  Activity,
  ChevronRight,
  ClipboardList,
  CreditCard,
  LayoutDashboard,
  MoreHorizontal,
  RefreshCw,
  Settings2,
  Stethoscope,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { AppHeader } from "@/components/app-header";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { DashboardView } from "@/modules/dashboard/view";
import { PatientsView } from "@/modules/patients/view";
import { ConsultationView } from "@/modules/consultation/view";
import { PrescriptionsView } from "@/modules/prescriptions/view";
import { PaymentsView } from "@/modules/payments/view";
import { SyncView } from "@/modules/sync/view";
import { BackofficeView } from "@/modules/backoffice/view";
import { ROLE_VIEWS, useCurrentUser } from "@/lib/session";
import { useAppStore } from "@/lib/store";
import type { ViewId } from "@/lib/types";
import { cn } from "@/lib/utils";

interface NavItem {
  id: ViewId;
  label: string;
  icon: LucideIcon;
  description: string;
}

const NAV_GROUPS: { title: string; items: NavItem[] }[] = [
  {
    title: "Soins",
    items: [
      { id: "dashboard", label: "Tableau de bord", icon: LayoutDashboard, description: "Vue d'ensemble du poste" },
      { id: "patients", label: "Patients", icon: Users, description: "MPI : recherche, création, doublons" },
      { id: "consultation", label: "Consultation", icon: Stethoscope, description: "Examen clinique et ordonnance" },
      { id: "prescriptions", label: "Ordonnances", icon: ClipboardList, description: "Dispensation partielle, contre-entrées" },
    ],
  },
  {
    title: "Finances",
    items: [
      { id: "payments", label: "Paiements", icon: CreditCard, description: "FedaPay : 8 états, réconciliation" },
    ],
  },
  {
    title: "Système",
    items: [
      { id: "sync", label: "Synchronisation", icon: RefreshCw, description: "Outbox, delta, journal" },
      { id: "backoffice", label: "Back-office", icon: Settings2, description: "Utilisateurs, structures, rôles" },
    ],
  },
];

const ALL_ITEMS = NAV_GROUPS.flatMap((g) => g.items);

function viewTitle(view: ViewId): NavItem {
  return ALL_ITEMS.find((i) => i.id === view) ?? ALL_ITEMS[0];
}

const VIEWS: Record<ViewId, React.ComponentType> = {
  dashboard: DashboardView,
  patients: PatientsView,
  consultation: ConsultationView,
  prescriptions: PrescriptionsView,
  payments: PaymentsView,
  sync: SyncView,
  backoffice: BackofficeView,
};

function SidebarNav({
  groups,
  view,
  onNavigate,
}: {
  groups: { title: string; items: NavItem[] }[];
  view: ViewId;
  onNavigate: (id: ViewId) => void;
}) {
  return (
    <nav aria-label="Navigation principale" className="space-y-5">
      {groups.map((group) => (
        <div key={group.title}>
          <p className="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/80">
            {group.title}
          </p>
          <ul className="space-y-1">
            {group.items.map((item) => {
              const active = view === item.id;
              return (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => onNavigate(item.id)}
                    aria-current={active ? "page" : undefined}
                    className={cn(
                      "flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all",
                      "focus-visible:outline-2 focus-visible:outline-ring",
                      active
                        ? "bg-gradient-medical text-ink-medical font-semibold shadow-tile"
                        : "text-foreground/75 hover:bg-accent hover:text-accent-foreground",
                    )}
                  >
                    <item.icon className="h-4 w-4 shrink-0" aria-hidden="true" />
                    <span className="truncate">{item.label}</span>
                    {active && (
                      <ChevronRight
                        className="ml-auto h-4 w-4 shrink-0"
                        aria-hidden="true"
                      />
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

/** Barre du bas (mobile) : 4 vues principales du rôle + feuille « Plus ». */
function BottomBar({
  items,
  view,
  onNavigate,
}: {
  items: NavItem[];
  view: ViewId;
  onNavigate: (id: ViewId) => void;
}) {
  const [moreOpen, setMoreOpen] = useState(false);
  const primaryItems = items.slice(0, 4);
  const moreItems = items.slice(4);

  return (
    <div
      className="fixed inset-x-3 bottom-3 z-40 rounded-3xl border border-border bg-card/95 shadow-float backdrop-blur md:hidden"
      role="navigation"
      aria-label="Navigation mobile"
    >
      <nav className="mx-auto grid max-w-lg grid-cols-5 pb-safe pt-1">
        {primaryItems.map((item) => {
          const active = view === item.id;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate(item.id)}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex min-h-[52px] flex-col items-center justify-center gap-1 rounded-2xl px-1 py-1.5 text-[11px] font-medium transition-all active:scale-[0.96]",
                active
                  ? "text-primary"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <span
                className={cn(
                  "flex h-7 w-7 items-center justify-center rounded-full transition-all",
                  active && "bg-gradient-medical text-ink-medical shadow-tile",
                )}
              >
                <item.icon className="h-4 w-4" aria-hidden="true" />
              </span>
              <span className="truncate">{item.label.split(" ")[0]}</span>
            </button>
          );
        })}
        <Sheet open={moreOpen} onOpenChange={setMoreOpen}>
          <SheetTrigger asChild>
            <button
              type="button"
              className={cn(
                "flex min-h-[56px] flex-col items-center justify-center gap-1 px-1 py-2 text-[11px] font-medium",
                moreItems.some((i) => i.id === view)
                  ? "text-primary"
                  : "text-muted-foreground",
              )}
            >
              <MoreHorizontal className="h-5 w-5" aria-hidden="true" />
              <span>Plus</span>
            </button>
          </SheetTrigger>
          <SheetContent side="bottom" className="pb-safe">
            <SheetHeader>
              <SheetTitle className="text-base">Autres modules</SheetTitle>
              <SheetDescription>
                Modules complémentaires de votre rôle.
              </SheetDescription>
            </SheetHeader>
            <nav aria-label="Navigation secondaire" className="grid gap-2 px-4 pb-6 pt-2">
              {moreItems.map((item) => (
                <Button
                  key={item.id}
                  variant={view === item.id ? "default" : "outline"}
                  className="h-12 w-full justify-start gap-3"
                  onClick={() => {
                    onNavigate(item.id);
                    setMoreOpen(false);
                  }}
                >
                  <item.icon className="h-4 w-4" aria-hidden="true" />
                  <span className="flex-1 text-left">{item.label}</span>
                  <span className="text-[11px] font-normal opacity-70">
                    {item.description}
                  </span>
                </Button>
              ))}
            </nav>
          </SheetContent>
        </Sheet>
      </nav>
    </div>
  );
}

export function AppShell() {
  const view = useAppStore((s) => s.view);
  const goTo = useAppStore((s) => s.goTo);
  const hydrate = useAppStore((s) => s.hydrate);
  const hydrated = useAppStore((s) => s.hydrated);
  const syncing = useAppStore((s) => s.syncing);
  const user = useCurrentUser();

  /* Périmètre du rôle connecté : seules ces vues sont navigables. */
  const allowed = ROLE_VIEWS[user.role] ?? ALL_ITEMS.map((i) => i.id);
  const groups = NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((i) => allowed.includes(i.id)),
  })).filter((group) => group.items.length > 0);
  const allowedItems = groups.flatMap((group) => group.items);
  const navigate = (id: ViewId) =>
    goTo(allowed.includes(id) ? id : "dashboard");

  useEffect(() => {
    void hydrate();
  }, [hydrate]);

  const viewAllowed = allowed.includes(view);
  const effectiveView: ViewId = viewAllowed ? view : "dashboard";
  const CurrentView = VIEWS[effectiveView] ?? DashboardView;
  const title = viewTitle(effectiveView);

  return (
    <div className="flex min-h-screen w-full flex-col">
      <AppHeader />

      <div className="mx-auto flex w-full max-w-7xl flex-1 gap-6 px-3 py-4 pb-32 sm:px-6 md:pb-8">
        {/* Rail latéral (desktop) */}
        <aside
          className="sticky top-[72px] hidden h-fit w-56 shrink-0 md:block"
          aria-label="Navigation de l'application"
        >
          <SidebarNav groups={groups} view={effectiveView} onNavigate={navigate} />
        </aside>

        {/* Contenu de la vue active */}
        <main
          id="contenu-principal"
          className="min-w-0 flex-1"
          aria-live="polite"
        >
          <div className="mb-4 flex items-center gap-2.5 md:hidden">
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-medical text-ink-medical shadow-tile">
              <title.icon className="h-5 w-5" aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <h1 className="truncate text-base font-bold tracking-tight">
                {title.label}
              </h1>
              <p className="truncate text-xs text-muted-foreground">
                {title.description}
              </p>
            </div>
          </div>

          <AnimatePresence mode="wait">
            <motion.div
              key={effectiveView}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.18, ease: "easeOut" }}
            >
              {!hydrated ? (
                <div
                  className="flex min-h-[50vh] flex-col items-center justify-center gap-3 text-center"
                  role="status"
                  aria-label="Chargement du miroir local"
                >
                  <Activity
                    className={cn(
                      "h-8 w-8 text-primary",
                      syncing && "animate-pulse",
                    )}
                    aria-hidden="true"
                  />
                  <p className="text-sm text-muted-foreground">
                    Ouverture du miroir local (IndexedDB)…
                  </p>
                </div>
              ) : (
                <CurrentView />
              )}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      <footer className="mt-auto border-t border-border bg-card">
        <div className="mx-auto flex max-w-7xl flex-col items-start justify-between gap-2 px-4 py-4 text-xs text-muted-foreground sm:flex-row sm:items-center sm:px-6">
          <p>
            PUBLIC HEALTH v0.5 · ID / HUB / DATA · Patient d'abord · Burkina
            Faso
          </p>
          <p>
            Données de santé : jamais dans les analytics · OWASP ASVS L2 visé
          </p>
        </div>
      </footer>

      <BottomBar items={allowedItems} view={effectiveView} onNavigate={navigate} />
    </div>
  );
}
