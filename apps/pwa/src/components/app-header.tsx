import { HeartPulse } from "lucide-react";
import { SyncStatusBadge } from "@/components/sync-status-badge";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";

interface AppHeaderProps {
  /** Nom affiché de l'utilisateur connecté */
  userName?: string;
  /** Structure sanitaire de rattachement */
  facility?: string;
  queuedOperations?: number;
}

/**
 * AppHeader — barre applicative permanente.
 * Contient : identité du produit, état réseau/synchronisation, utilisateur.
 * L'état réseau est visible en permanence (principe UX n°1).
 */
export function AppHeader({
  userName = "Aminata S.",
  facility = "CSPS Ouaga 12",
  queuedOperations = 0,
}: AppHeaderProps) {
  const initials = userName
    .split(" ")
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();

  return (
    <header
      role="banner"
      className="sticky top-0 z-40 border-b border-border/80 bg-card/90 backdrop-blur supports-[backdrop-filter]:bg-card/75"
    >
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between gap-3 px-4 sm:px-6">
        <div className="flex min-w-0 items-center gap-2.5">
          <span
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground"
            aria-hidden="true"
          >
            <HeartPulse className="h-5 w-5" />
          </span>
          <div className="min-w-0 leading-tight">
            <p className="truncate text-sm font-semibold tracking-tight">
              PUBLIC HEALTH
            </p>
            <p className="hidden truncate text-xs text-muted-foreground sm:block">
              Burkina Faso · Plateforme nationale
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 sm:gap-3">
          <SyncStatusBadge queuedOperations={queuedOperations} />
          <div
            className="flex items-center gap-2 rounded-full border border-border py-1 pl-1 pr-3"
            aria-label={`Connectée : ${userName}, ${facility}`}
          >
            <Avatar className="h-7 w-7">
              <AvatarFallback className="bg-primary/10 text-[11px] font-medium text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <span className="hidden max-w-36 truncate text-xs text-muted-foreground md:block">
              {userName} · {facility}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}
