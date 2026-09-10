"use client";

/**
 * Module Back-office (E6) — états vides partagés (utilisateurs / structures) :
 * squelettes pendant le chargement, exigence honnête de réseau lorsque le
 * miroir de gouvernance n'a jamais été rempli.
 */

import { Skeleton } from "@/components/ui/skeleton";
import { TriangleAlert, WifiOff } from "lucide-react";
import { cn } from "@/lib/utils";

interface BackofficeEmptyProps {
  /** Chargement en cours (en ligne, miroir vide, promesse en vol). */
  loading: boolean;
  /** Réseau simulé coupé : la liste exige une première connexion. */
  offline: boolean;
  /** Message d'échec du dernier chargement direct (api-client). */
  error: string | null;
}

export function BackofficeEmpty({
  loading,
  offline,
  error,
}: BackofficeEmptyProps) {
  if (loading) {
    return (
      <div
        className="space-y-2"
        role="status"
        aria-label="Chargement des données de gouvernance"
      >
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }

  if (offline) {
    return (
      <div
        className="flex flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed py-10 text-center"
        role="status"
      >
        <WifiOff
          className="size-7 text-amber-600 dark:text-amber-400"
          aria-hidden="true"
        />
        <p className="text-sm font-medium">
          Requiert une première connexion pour charger la liste
        </p>
        <p className="max-w-xs text-xs text-muted-foreground">
          Rebranchez le réseau (vue Synchronisation) puis rafraîchissez : la
          gouvernance n&apos;est pas encore dans le miroir local.
        </p>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="flex flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed border-destructive/30 py-10 text-center"
        role="alert"
      >
        <TriangleAlert className="size-7 text-destructive" aria-hidden="true" />
        <p className="text-sm font-medium">Chargement impossible</p>
        <p className={cn("max-w-xs text-xs text-muted-foreground")}>{error}</p>
        <p className="max-w-xs text-xs text-muted-foreground">
          Réessayez via « Rafraîchir ».
        </p>
      </div>
    );
  }

  return (
    <div
      className="flex flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed py-10 text-center"
      role="status"
    >
      <p className="text-sm font-medium">Aucune donnée de gouvernance</p>
      <p className="max-w-xs text-xs text-muted-foreground">
        Le serveur n&apos;a renvoyé aucun compte pour cette structure.
      </p>
    </div>
  );
}
