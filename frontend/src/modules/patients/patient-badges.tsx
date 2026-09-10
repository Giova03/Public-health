"use client";

/**
 * Module Patients (E1) — badges partagés (liste + fiche).
 * Couleurs : tokens sémantiques, à l'exception de l'ambre « en attente »,
 * volontairement distinct de l'état nominal comme de l'erreur.
 */

import { CloudUpload, Mars, Venus } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { ageLabel } from "./patient-utils";

export function GenderBadge({
  gender,
  className,
}: {
  gender: "M" | "F";
  className?: string;
}) {
  const Icon = gender === "M" ? Mars : Venus;
  return (
    <Badge variant="secondary" className={cn("gap-1", className)}>
      <Icon className="h-3 w-3" aria-hidden="true" />
      {gender === "M" ? "Homme" : "Femme"}
    </Badge>
  );
}

export function AgeBadge({
  birthDate,
  className,
}: {
  birthDate: string;
  className?: string;
}) {
  return (
    <Badge variant="outline" className={cn("tnum", className)}>
      {ageLabel(birthDate)}
    </Badge>
  );
}

/** Dossier créé/modifié hors ligne : pas encore purgé de l'outbox. */
export function PendingSyncBadge({ className }: { className?: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        "gap-1 border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
        className,
      )}
    >
      <CloudUpload className="h-3 w-3" aria-hidden="true" />
      En attente
    </Badge>
  );
}

/** Référence MPI non encore attribuée (le serveur la donne au drain). */
export function RefSyncBadge({ className }: { className?: string }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        "border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400",
        className,
      )}
    >
      Réf. à la synchronisation
    </Badge>
  );
}

/** Dossier connu du serveur mais absent du miroir local (recherche enrichie). */
export function ServerOnlyBadge({ className }: { className?: string }) {
  return (
    <Badge variant="outline" className={cn("text-muted-foreground", className)}>
      Serveur
    </Badge>
  );
}
