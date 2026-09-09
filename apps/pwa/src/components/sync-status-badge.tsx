"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { CloudOff, RefreshCw, Wifi } from "lucide-react";
import { cn } from "@/lib/utils";

type SyncState = "online" | "offline" | "syncing";

interface SyncStatusBadgeProps {
  /** Nombre d'opérations en file d'attente de synchronisation */
  queuedOperations?: number;
  className?: string;
}

/**
 * SyncStatusBadge — le « signal vital » de l'application.
 * Règle d'or UX : l'état réseau est TOUJOURS visible, jamais un mystère.
 * Hors ligne = honnêteté : on affiche la file, jamais un faux succès.
 */
export function SyncStatusBadge({
  queuedOperations = 0,
  className,
}: SyncStatusBadgeProps) {
  const [isOnline, setIsOnline] = useState(true);

  useEffect(() => {
    const update = () => setIsOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);

  const state: SyncState = !isOnline ? "offline" : "syncing";

  if (state === "offline") {
    return (
      <Badge
        variant="outline"
        role="status"
        aria-label={`Hors ligne, ${queuedOperations} opérations en file d'attente`}
        className={cn(
          "gap-1.5 border-amber-300 bg-amber-50 text-amber-800",
          className,
        )}
      >
        <CloudOff className="h-3.5 w-3.5" aria-hidden="true" />
        Hors ligne · {queuedOperations} en file
      </Badge>
    );
  }

  if (state === "syncing" && queuedOperations > 0) {
    return (
      <Badge
        variant="outline"
        role="status"
        aria-label={`Synchronisation en cours, ${queuedOperations} opérations`}
        className={cn("gap-1.5 border-primary/30 bg-primary/5 text-primary", className)}
      >
        <RefreshCw className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
        Synchronisation · {queuedOperations}
      </Badge>
    );
  }

  return (
    <Badge
      variant="outline"
      role="status"
      aria-label="En ligne, synchronisé"
      className={cn("gap-1.5 border-emerald-300 bg-emerald-50 text-emerald-800", className)}
    >
      <Wifi className="h-3.5 w-3.5" aria-hidden="true" />
      En ligne
    </Badge>
  );
}
