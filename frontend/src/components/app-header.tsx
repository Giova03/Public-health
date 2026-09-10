"use client";

import { useTheme } from "next-themes";
import { HeartPulse, Moon, Sun } from "lucide-react";
import { LiveSyncBadge } from "@/components/live-sync-badge";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { CURRENT_USER, STAFF_ROLE_LABELS } from "@/lib/demo/reference";

/**
 * AppHeader — barre applicative permanente.
 *
 * Contient : identité du produit, signal réseau/synchronisation toujours
 * visible (UX n°1), bascule du mode coupé (pédagogie offline), bascule
 * clair/sombre, identité de l'agent connecté.
 */
export function AppHeader() {
  const { resolvedTheme, setTheme } = useTheme();
  const hydrated = useIsHydrated();
  const isDark = hydrated && resolvedTheme === "dark";

  const initials = CURRENT_USER.fullName
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
      <div className="mx-auto flex h-14 w-full max-w-7xl items-center justify-between gap-2 px-3 sm:px-6">
        <div className="flex min-w-0 items-center gap-2.5">
          <span
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-medical text-ink-medical shadow-tile"
            aria-hidden="true"
          >
            <HeartPulse className="h-5 w-5" />
          </span>
          <div className="min-w-0 leading-tight">
            <p className="truncate text-sm font-bold tracking-tight text-primary">
              PUBLIC HEALTH
            </p>
            <p className="hidden truncate text-xs text-muted-foreground sm:block">
              Burkina Faso · Plateforme nationale
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1.5 sm:gap-3">
          <LiveSyncBadge />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8"
            onClick={() => setTheme(isDark ? "light" : "dark")}
            title="Basculer clair/sombre"
            aria-label="Basculer le thème clair ou sombre"
          >
            {isDark ? (
              <Sun className="h-4 w-4" aria-hidden="true" />
            ) : (
              <Moon className="h-4 w-4" aria-hidden="true" />
            )}
          </Button>
          <div
            className="hidden items-center gap-2 rounded-full border border-border py-1 pl-1 pr-3 sm:flex"
            aria-label={`Connectée : ${CURRENT_USER.fullName}, ${STAFF_ROLE_LABELS[CURRENT_USER.role]}, ${CURRENT_USER.facility}`}
            title={`${STAFF_ROLE_LABELS[CURRENT_USER.role]} · ${CURRENT_USER.facility}`}
          >
            <Avatar className="h-7 w-7">
              <AvatarFallback className="bg-primary/10 text-[11px] font-medium text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <span className="max-w-28 truncate text-xs font-medium leading-tight">
              {CURRENT_USER.fullName}
            </span>
          </div>
        </div>
      </div>
    </header>
  );
}
