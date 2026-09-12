"use client";

/**
 * Module Patients (E1) — vue liste (recherche miroir + création).
 *
 * La recherche est instantanée sur le miroir local (IndexedDB → store) ;
 * en ligne, elle est enrichie côté serveur (debounce 300 ms, fusion par
 * id, échec silencieux). La création orchestre le contrat 409 : le
 * formulaire reste ouvert sous le dialogue de doublons, pour permettre
 * la correction de saisie après annulation.
 */

import { useState } from "react";
import { motion } from "framer-motion";
import {
  ChevronRight,
  Loader2,
  MapPin,
  Phone,
  Search,
  SearchX,
  UserPlus,
  Users,
} from "lucide-react";
import type { CreatePatientInput, DuplicateCandidate, Patient } from "@/lib/types";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";
import { avatarTone, fullName, initials } from "./patient-utils";
import {
  AgeBadge,
  GenderBadge,
  PendingSyncBadge,
  RefSyncBadge,
  ServerOnlyBadge,
} from "./patient-badges";
import { usePatientSearch } from "./use-patient-search";
import { PatientFormDialog, type CreateStatus } from "./patient-form";
import { DuplicatesDialog, type ConflictState } from "./duplicates-dialog";

export function PatientsList() {
  const patients = useAppStore((s) => s.patients);
  const hydrated = useAppStore((s) => s.hydrated);
  const online = useAppStore((s) => s.simulatedOnline);
  const selectPatient = useAppStore((s) => s.selectPatient);
  const { toast } = useToast();
  // P0-3 (audit, étape 3) : création = patient:ecrire. Le pharmacien, le
  // caissier et le superviseur (patient:lire) restent en lecture/Recherche.
  const peutCreer = usePermission("patient:ecrire");

  const { query, setQuery, results, serverOnlyIds, searching, mirrorCount } =
    usePatientSearch(patients, online);

  const [formOpen, setFormOpen] = useState(false);
  const [conflict, setConflict] = useState<ConflictState | null>(null);

  /* ---------------------- Cycle de création (contrat E1) --------------- */

  const handleCreated = (patient: Patient, status: CreateStatus) => {
    setFormOpen(false);
    setConflict(null);
    selectPatient(patient.id);
    if (status === "queued") {
      toast({
        title: "Dossier créé hors ligne",
        description:
          "Il sera synchronisé à la reconnexion et recevra alors sa référence PH.",
      });
    } else if (status === "replayed") {
      toast({
        title: "Demande déjà enregistrée",
        description: `Le dossier ${patient.phReference} existait déjà — rejeu idempotent.`,
      });
    } else {
      toast({
        title: "Dossier créé",
        description: `Référence ${patient.phReference} attribuée au registre national.`,
      });
    }
  };

  const handleConflict = (
    input: CreatePatientInput,
    candidates: DuplicateCandidate[],
  ) => {
    setConflict({ input, candidates });
  };

  const handleOpenExisting = (patient: Patient) => {
    setConflict(null);
    setFormOpen(false);
    selectPatient(patient.id);
    toast({
      title: "Dossier existant sélectionné",
      description: `${fullName(patient.name)} — ${patient.phReference}.`,
    });
  };

  const handleForcedCreated = (patient: Patient, status: CreateStatus) => {
    setConflict(null);
    setFormOpen(false);
    selectPatient(patient.id);
    if (status === "queued") {
      toast({
        title: "Dossier créé hors ligne",
        description:
          "Il sera synchronisé à la reconnexion et recevra alors sa référence PH.",
      });
    } else if (status === "replayed") {
      toast({
        title: "Création déjà enregistrée",
        description: "Rejeu idempotent de la même demande (clientRequestId).",
      });
    } else {
      toast({
        title: "Dossier créé — création forcée",
        description: `Référence ${patient.phReference} · motif tracé en audit.`,
      });
    }
  };

  /* -------------------------------- Rendu ------------------------------- */

  return (
    <div className="space-y-4">
      {/* Recherche + création */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search
            className="pointer-events-none absolute top-1/2 left-3.5 h-4 w-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Nom, prénom, téléphone ou référence PH…"
            aria-label="Rechercher un patient"
            className="h-11 pr-10 pl-10"
          />
          {searching && (
            <Loader2
              className="absolute top-1/2 right-3.5 h-4 w-4 -translate-y-1/2 animate-spin text-muted-foreground"
              aria-hidden="true"
            />
          )}
        </div>
        {peutCreer ? (
          <Button
            variant="medical"
            className="h-11 w-full px-5 sm:w-auto"
            onClick={() => setFormOpen(true)}
          >
            <UserPlus className="h-4 w-4" aria-hidden="true" />
            Nouveau patient
          </Button>
        ) : (
          <p
            className="w-full text-xs text-muted-foreground sm:w-auto"
            title="Masquage P0-3 : votre rôle ne porte pas patient:ecrire"
          >
            Création masquée : <code>patient:ecrire</code> requis — la recherche
            et la consultation des dossiers restent ouvertes.
          </p>
        )}
      </div>

      {/* Compteur de résultats */}
      {hydrated && (
        <p
          role="status"
          aria-live="polite"
          className="tnum text-xs text-muted-foreground"
        >
          {query.trim() ? (
            <>
              {results.length} {results.length > 1 ? "dossiers trouvés" : "dossier trouvé"}
              <span className="text-muted-foreground/80">
                {" "}· sur {mirrorCount} dans le miroir
              </span>
            </>
          ) : (
            <>
              {results.length} {results.length > 1 ? "dossiers" : "dossier"}{" "}
              <span className="text-muted-foreground/80">· miroir local</span>
            </>
          )}
          {serverOnlyIds.size > 0 && (
            <span className="text-primary">
              {" "}· {serverOnlyIds.size} du serveur
            </span>
          )}
        </p>
      )}

      {/* Contenu */}
      {!hydrated ? (
        <ListSkeleton />
      ) : results.length === 0 ? (
        <EmptyResults
          query={query.trim()}
          online={online}
          peutCreer={peutCreer}
          onCreate={() => setFormOpen(true)}
        />
      ) : (
        <Card className="divide-y overflow-hidden py-0 shadow-xs">
          <ul>
            {results.map((patient, i) => (
              <li key={patient.id}>
                <motion.button
                  type="button"
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{
                    duration: 0.15,
                    delay: Math.min(i * 0.03, 0.3),
                  }}
                  className={cn(
                    "flex min-h-11 w-full items-center gap-3 px-4 py-3 text-left",
                    "transition-colors hover:bg-accent/50 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring",
                  )}
                  onClick={() => selectPatient(patient.id)}
                >
                  <Avatar className="h-10 w-10 shrink-0 border">
                    <AvatarFallback
                      className={`text-xs font-semibold ${avatarTone(patient.id)}`}
                    >
                      {initials(patient.name)}
                    </AvatarFallback>
                  </Avatar>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm">
                      <span className="font-semibold">{patient.name.family}</span>{" "}
                      <span className="text-foreground/80">
                        {patient.name.given}
                      </span>
                    </span>
                    <span className="block truncate font-mono text-[11px] text-muted-foreground">
                      {patient.phReference || "réf. à la synchronisation"}
                    </span>
                    <span className="mt-1 flex flex-wrap items-center gap-1.5">
                      <GenderBadge gender={patient.gender} />
                      <AgeBadge birthDate={patient.birthDate} />
                      {patient.pendingSync && <PendingSyncBadge />}
                      {!patient.phReference && <RefSyncBadge />}
                      {serverOnlyIds.has(patient.id) && <ServerOnlyBadge />}
                    </span>
                    <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                      {patient.phone && (
                        <span className="inline-flex items-center gap-1">
                          <Phone className="h-3 w-3" aria-hidden="true" />
                          <span className="tnum">{patient.phone}</span>
                        </span>
                      )}
                      <span className="inline-flex min-w-0 items-center gap-1">
                        <MapPin className="h-3 w-3 shrink-0" aria-hidden="true" />
                        <span className="truncate">{patient.facility}</span>
                      </span>
                    </span>
                  </span>
                  <ChevronRight
                    className="h-4 w-4 shrink-0 text-muted-foreground"
                    aria-hidden="true"
                  />
                </motion.button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {/* Cycle de création : formulaire puis dialogue 409 par-dessus */}
      <PatientFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        onConflict={handleConflict}
        onCreated={handleCreated}
      />
      <DuplicatesDialog
        conflict={conflict}
        onOpenChange={(open) => {
          if (!open) setConflict(null);
        }}
        onOpenExisting={handleOpenExisting}
        onForcedCreated={handleForcedCreated}
        onConflictAgain={(candidates) =>
          setConflict((prev) => (prev ? { ...prev, candidates } : prev))
        }
      />
    </div>
  );
}

/* ------------------------------ Sous-vues ------------------------------- */

function ListSkeleton() {
  return (
    <Card className="divide-y overflow-hidden py-0 shadow-xs">
      <ul aria-hidden="true">
        {[0, 1, 2, 3, 4].map((i) => (
          <li key={i} className="flex items-center gap-3 px-4 py-3">
            <Skeleton className="h-10 w-10 shrink-0 rounded-full" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-3 w-28" />
              <Skeleton className="h-3.5 w-48" />
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function EmptyResults({
  query,
  online,
  peutCreer,
  onCreate,
}: {
  query: string;
  online: boolean;
  /** P0-3 : sans patient:ecrire, pas de CTA de création — on l'explique. */
  peutCreer: boolean;
  onCreate: () => void;
}) {
  if (query === "") {
    return (
      <Card className="px-6 py-12">
        <div className="mx-auto flex max-w-sm flex-col items-center gap-3 text-center">
          <span
            className="flex h-14 w-14 items-center justify-center rounded-full bg-primary/10 text-primary"
            aria-hidden="true"
          >
            <Users className="h-7 w-7" />
          </span>
          <div>
            <p className="font-medium">Aucun dossier dans le miroir local</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Le miroir se remplit à la synchronisation. Si le réseau est
              coupé, les dossiers nationaux arriveront à la reconnexion.
            </p>
          </div>
          {peutCreer ? (
            <Button variant="outline" className="min-h-11" onClick={onCreate}>
              <UserPlus className="h-4 w-4" aria-hidden="true" />
              Créer un dossier patient
            </Button>
          ) : (
            <p className="text-xs text-muted-foreground">
              La création de dossier exige <code>patient:ecrire</code> — pas
              porté par votre rôle.
            </p>
          )}
        </div>
      </Card>
    );
  }

  return (
    <Card className="px-6 py-12">
      <div className="mx-auto flex max-w-sm flex-col items-center gap-3 text-center">
        <span
          className="flex h-14 w-14 items-center justify-center rounded-full bg-muted text-muted-foreground"
          aria-hidden="true"
        >
          <SearchX className="h-7 w-7" />
        </span>
        <div>
          <p className="font-medium">
            Aucun résultat pour «&nbsp;{query}&nbsp;»
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            Vérifiez l&apos;orthographe — les accents sont ignorés
            («&nbsp;ouedraogo&nbsp;» trouve «&nbsp;OUÉDRAOGO&nbsp;»). Vous
            pouvez aussi chercher par téléphone ou référence PH.
          </p>
          {!online && (
            <p className="mt-2 text-xs text-amber-700 dark:text-amber-400">
              Hors ligne&nbsp;: seuls les dossiers du miroir local sont
              consultables.
            </p>
          )}
        </div>
      </div>
    </Card>
  );
}
