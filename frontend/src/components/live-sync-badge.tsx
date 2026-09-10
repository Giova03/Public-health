"use client";

import { useEffect, useState } from "react";
import { CloudOff, RefreshCw, Wifi, WifiOff } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/lib/store";

/**
 * LiveSyncBadge — le « signal vital » de l'application (règle d'or UX n°1).
 *
 * État affiché = simulation réseau ET état réel du navigateur ET moteur :
 *  - hors ligne (coupé) : file d'attente affichée, jamais un faux succès ;
 *  - en ligne + file non vide : drain en cours ou à déclencher ;
 *  - en ligne + synchronisé : vert, curseur et horodatage à jour.
 *
 * Le bouton adjacent simule la coupure réseau (pédagogie offline-first) :
 * il alimente le mode « réseau coupé » de la démonstration.
 */
export function LiveSyncBadge({ className }: { className?: string }) {
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const syncing = useAppStore((s) => s.syncing);
  const outboxCount = useAppStore((s) => s.outbox.length);
  const toggleOnline = useAppStore((s) => s.toggleOnline);
  const [navigatorOnline, setNavigatorOnline] = useState(true);

  useEffect(() => {
    const update = () => setNavigatorOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);

  const online = simulatedOnline && navigatorOnline;

  return (
    <div className={cn("flex items-center gap-1.5", className)}>
      <Badge
        role="status"
        variant="outline"
        aria-label={
          !online
            ? `Hors ligne, ${outboxCount} opérations en file d'attente`
            : syncing
              ? "Synchronisation en cours"
              : outboxCount > 0
                ? `En ligne, ${outboxCount} opérations en attente de drain`
                : "En ligne, synchronisé"
        }
        className={cn(
          "gap-1.5 whitespace-nowrap",
          !online && "border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300",
          online && outboxCount > 0 && !syncing && "border-primary/30 bg-primary/5 text-primary",
          online && syncing && "border-primary/30 bg-primary/5 text-primary",
          online && outboxCount === 0 && !syncing && "border-emerald-300 bg-emerald-50 text-emerald-800 dark:border-emerald-500/40 dark:bg-emerald-500/10 dark:text-emerald-300",
        )}
      >
        {!online && <CloudOff className="h-3.5 w-3.5" aria-hidden="true" />}
        {online && syncing && (
          <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
        )}
        {online && !syncing && <Wifi className="h-3.5 w-3.5" aria-hidden="true" />}
        {!online
          ? `Hors ligne · ${outboxCount} en file`
          : syncing
            ? `Synchro · ${outboxCount}`
            : outboxCount > 0
              ? `${outboxCount} en attente`
              : "Synchronisé"}
      </Badge>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-8 w-8"
        onClick={toggleOnline}
        title={
          simulatedOnline
            ? "Simuler une coupure réseau (démonstration offline-first)"
            : "Rétablir le réseau et lancer la synchronisation"
        }
        aria-label={
          simulatedOnline
            ? "Simuler une coupure réseau"
            : "Rétablir le réseau"
        }
      >
        {simulatedOnline ? (
          <Wifi className="h-4 w-4" aria-hidden="true" />
        ) : (
          <WifiOff className="h-4 w-4 text-amber-600" aria-hidden="true" />
        )}
      </Button>
    </div>
  );
}
