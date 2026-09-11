"use client";

/**
 * Module Back-office (E6) — gouvernance de base (lecture seule) :
 * indicateurs dérivés du miroir local, comptes du personnel et structures
 * de santé. Le rafraîchissement passe par l'action du store ; la vue
 * complète le filet de sécurité au montage si le miroir de gouvernance est
 * vide alors que le réseau est là (promesse .then — jamais de setState
 * synchrone dans un effet).
 */

import { useEffect, useMemo, useState } from "react";
import { motion } from "framer-motion";
import type { Variants } from "framer-motion";
import {
  ClipboardList,
  Hourglass,
  KeyRound,
  Lock,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { FacilitiesTab } from "./facilities-tab";
import { RolesMatrixTab } from "./roles-matrix-tab";
import { UsersTab } from "./users-tab";
import {
  computeGovernanceStats,
  findAdminsWithoutMfa,
} from "./backoffice-helpers";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/hooks/use-toast";
import { getBackoffice } from "@/lib/api-client";
import { useAppStore } from "@/lib/store";
import type { HealthFacility, StaffUser } from "@/lib/types";
import { cn } from "@/lib/utils";

const CONTAINER_VARIANTS: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.05 } },
};

const SECTION_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.22, ease: "easeOut" },
  },
};

/** Repli local quand le miroir du store est vide (chargement direct). */
type RemoteBackoffice =
  | { state: "idle" }
  | { state: "ok"; users: StaffUser[]; facilities: HealthFacility[] }
  | { state: "error"; message: string };

export function BackofficeView() {
  const hydrated = useAppStore((s) => s.hydrated);
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const users = useAppStore((s) => s.users);
  const facilities = useAppStore((s) => s.facilities);
  const refreshBackoffice = useAppStore((s) => s.refreshBackoffice);
  const patients = useAppStore((s) => s.patients);
  const prescriptions = useAppStore((s) => s.prescriptions);
  const payments = useAppStore((s) => s.payments);
  const { toast } = useToast();

  const [refreshing, setRefreshing] = useState(false);
  const [remote, setRemote] = useState<RemoteBackoffice>({ state: "idle" });

  /* Filet de sécurité : miroir de gouvernance vide + réseau présent →
     chargement direct via l'api-client (setState uniquement dans .then). */
  useEffect(() => {
    if (!hydrated || !simulatedOnline || users.length > 0) return;
    let active = true;
    getBackoffice()
      .then((data) => {
        if (active) {
          setRemote({
            state: "ok",
            users: data.users,
            facilities: data.facilities,
          });
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setRemote({
            state: "error",
            message:
              error instanceof Error ? error.message : "Erreur inconnue",
          });
        }
      });
    return () => {
      active = false;
    };
  }, [hydrated, simulatedOnline, users.length]);

  /* Source d'affichage : le miroir du store d'abord, le repli ensuite. */
  const effectiveUsers =
    users.length > 0
      ? users
      : remote.state === "ok"
        ? remote.users
        : [];
  const effectiveFacilities =
    users.length > 0
      ? facilities
      : remote.state === "ok"
        ? remote.facilities
        : [];

  const loading =
    hydrated &&
    simulatedOnline &&
    effectiveUsers.length === 0 &&
    remote.state === "idle";

  const stats = useMemo(
    () => computeGovernanceStats({ patients, prescriptions, payments }),
    [patients, prescriptions, payments],
  );

  const adminsSansMfa = findAdminsWithoutMfa(effectiveUsers);

  /** Rafraîchissement honnête : le store remplace le tableau en cas de
      succès — l'identité de la référence distingue réussite et échec. */
  async function handleRefresh() {
    if (!simulatedOnline) {
      toast({
        variant: "destructive",
        title: "Hors ligne",
        description:
          "Le back-office requiert le réseau : rebranchez le réseau (vue Synchronisation), puis rafraîchissez.",
      });
      return;
    }
    setRefreshing(true);
    const before = useAppStore.getState().users;
    await refreshBackoffice();
    setRefreshing(false);
    const after = useAppStore.getState();
    if (after.users !== before && after.users.length > 0) {
      toast({
        title: "Gouvernance à jour",
        description: `${after.users.length} comptes · ${after.facilities.length} structures de santé.`,
      });
    } else {
      toast({
        variant: "destructive",
        title: "Échec du rafraîchissement",
        description:
          "Le serveur de gouvernance n'a pas répondu — les données du miroir restent affichées.",
      });
    }
  }

  return (
    <div className="space-y-4 md:space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="hidden md:flex md:items-center md:gap-3">
          <span
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-medical text-ink-medical shadow-tile"
            aria-hidden="true"
          >
            <ShieldCheck className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-xl font-semibold tracking-tight">
              Back-office — gouvernance de base
            </h1>
            <p className="mt-0.5 text-sm text-muted-foreground">
              Comptes, structures, matrice des rôles et indicateurs de
              supervision (lecture seule).
            </p>
          </div>
        </div>
        <div className="flex w-full flex-wrap items-center gap-2 md:w-auto">
          <Badge
            variant="outline"
            className="gap-1 text-muted-foreground"
            title="Démonstration : aucune écriture de gouvernance n'est possible"
          >
            <Lock aria-hidden="true" />
            Lecture seule (démonstration)
          </Badge>
          <Button
            variant="outline"
            size="lg"
            className="h-11 flex-1 sm:flex-none"
            onClick={() => void handleRefresh()}
            disabled={refreshing}
            aria-label="Rafraîchir les données de gouvernance"
          >
            <RefreshCw
              className={cn(refreshing && "animate-spin")}
              aria-hidden="true"
            />
            Rafraîchir
          </Button>
        </div>
      </header>

      <motion.div
        variants={CONTAINER_VARIANTS}
        initial="hidden"
        animate="visible"
        className="space-y-4 md:space-y-6"
      >
        {/* Indicateurs (miroir local) */}
        <motion.div
          variants={SECTION_VARIANTS}
          className="grid grid-cols-2 gap-3 md:grid-cols-4"
          aria-label="Indicateurs de supervision"
        >
          <StatTile
            icon={Users}
            label="Patients actifs"
            value={hydrated ? stats.activePatients : null}
            iconClass="bg-primary/10 text-primary"
            title="Dossiers actifs du miroir local"
          />
          <StatTile
            icon={ClipboardList}
            label="Ordonnances en cours"
            value={hydrated ? stats.activePrescriptions : null}
            iconClass="bg-primary/10 text-primary"
            title="Ordonnances ACTIVE du miroir local"
          />
          <StatTile
            icon={Hourglass}
            label="Paiements en attente"
            value={hydrated ? stats.pendingPayments : null}
            iconClass="bg-amber-500/10 text-amber-600 dark:text-amber-400"
            title="États INITIATED et PENDING du miroir local"
          />
          <StatTile
            icon={TriangleAlert}
            label="Paiements en échec"
            value={hydrated ? stats.failedPayments : null}
            iconClass={
              stats.failedPayments > 0
                ? "bg-destructive/10 text-destructive"
                : "bg-muted text-muted-foreground"
            }
            valueClass={stats.failedPayments > 0 ? "text-destructive" : undefined}
            title="États FAILED du miroir local"
          />
        </motion.div>

        {/* Onglets Utilisateurs / Structures / Rôles et permissions */}
        <motion.div variants={SECTION_VARIANTS}>
          <Tabs defaultValue="users">
            <TabsList className="w-full sm:w-auto">
              <TabsTrigger
                value="users"
                className="flex-1 gap-1.5 sm:flex-none"
              >
                <Users aria-hidden="true" />
                Utilisateurs
              </TabsTrigger>
              <TabsTrigger
                value="facilities"
                className="flex-1 gap-1.5 sm:flex-none"
              >
                <ClipboardList aria-hidden="true" />
                Structures
              </TabsTrigger>
              <TabsTrigger
                value="roles"
                className="flex-1 gap-1.5 sm:flex-none"
              >
                <KeyRound aria-hidden="true" />
                Rôles et permissions
              </TabsTrigger>
            </TabsList>
            <TabsContent value="users" className="mt-3">
              <UsersTab
                users={effectiveUsers}
                loading={loading}
                offline={!simulatedOnline}
                error={remote.state === "error" ? remote.message : null}
              />
            </TabsContent>
            <TabsContent value="facilities" className="mt-3">
              <FacilitiesTab
                facilities={effectiveFacilities}
                loading={loading}
                offline={!simulatedOnline}
                error={remote.state === "error" ? remote.message : null}
              />
            </TabsContent>
            <TabsContent value="roles" className="mt-3">
              <RolesMatrixTab />
            </TabsContent>
          </Tabs>
        </motion.div>

        {/* Note ADR sécurité (MFA) */}
        <motion.div variants={SECTION_VARIANTS}>
          <Card
            className={cn(
              "gap-0 py-0",
              adminsSansMfa.length > 0 && "border-amber-500/40 bg-amber-500/5",
            )}
          >
            <CardContent className="flex items-start gap-2.5 px-4 py-3 text-xs sm:px-6 sm:text-sm">
              <ShieldCheck
                className={cn(
                  "mt-0.5 size-4 shrink-0",
                  adminsSansMfa.length > 0
                    ? "text-amber-600 dark:text-amber-300"
                    : "text-primary",
                )}
                aria-hidden="true"
              />
              <p className="leading-relaxed text-muted-foreground">
                Administrateurs : MFA obligatoire (ADR sécurité) —{" "}
                {adminsSansMfa.length > 0 ? (
                  <>
                    <span className="font-medium text-amber-700 dark:text-amber-300">
                      {adminsSansMfa.length} compte(s) ADMIN sans MFA
                    </span>{" "}
                    seraient suspendus.
                  </>
                ) : (
                  <>
                    les comptes ADMIN sans MFA seraient suspendus.
                  </>
                )}
              </p>
            </CardContent>
          </Card>
        </motion.div>
      </motion.div>
    </div>
  );
}

/* ----------------------------- Stat tile ------------------------------- */

interface StatTileProps {
  icon: LucideIcon;
  label: string;
  value: number | null;
  iconClass: string;
  valueClass?: string;
  title?: string;
}

function StatTile({
  icon: Icon,
  label,
  value,
  iconClass,
  valueClass,
  title,
}: StatTileProps) {
  return (
    <Card className="gap-2 py-3" title={title}>
      <CardContent className="px-3">
        <div className="flex items-center justify-between gap-2">
          <p className="truncate text-[11px] font-medium uppercase tracking-wider text-muted-foreground/80">
            {label}
          </p>
          <span
            className={cn(
              "flex size-7 shrink-0 items-center justify-center rounded-full",
              iconClass,
            )}
            aria-hidden="true"
          >
            <Icon className="size-4" />
          </span>
        </div>
        {value === null ? (
          <Skeleton className="mt-1.5 h-8 w-14" />
        ) : (
          <p
            className={cn(
              "tnum mt-1 text-2xl font-semibold tracking-tight",
              valueClass,
            )}
          >
            {value}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
