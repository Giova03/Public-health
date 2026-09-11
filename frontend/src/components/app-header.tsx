"use client";

import { useTheme } from "next-themes";
import { HeartPulse, LogOut, Moon, Sun } from "lucide-react";
import { LiveSyncBadge } from "@/components/live-sync-badge";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { STAFF_ROLE_LABELS } from "@/lib/demo/reference";
import { useCurrentUser, useSessionStore } from "@/lib/session";

/**
 * AppHeader — barre applicative permanente du poste de santé.
 *
 * Contient : identité du produit, signal réseau/synchronisation toujours
 * visible (UX n°1), bascule du mode coupé (pédagogie offline), bascule
 * clair/sombre, identité du professionnel connecté (session) et
 * déconnexion.
 */
export function AppHeader() {
  const { resolvedTheme, setTheme } = useTheme();
  const hydrated = useIsHydrated();
  const isDark = hydrated && resolvedTheme === "dark";
  const user = useCurrentUser();
  const logout = useSessionStore((s) => s.logout);

  const initials = user.fullName
    .split(" ")
    .map((part) => part[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
  const roleLabel = STAFF_ROLE_LABELS[user.role];

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
            aria-label={`Connecté : ${user.fullName}, ${roleLabel}, ${user.facility}`}
            title={`${roleLabel} · ${user.facility}`}
          >
            <Avatar className="h-7 w-7">
              <AvatarFallback className="bg-primary/10 text-[11px] font-medium text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <span className="max-w-28 truncate text-xs font-medium leading-tight">
              {user.fullName}
            </span>
            <span className="hidden max-w-32 truncate rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary lg:inline">
              {roleLabel.split(" (")[0]}
            </span>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 text-muted-foreground hover:text-destructive"
            onClick={logout}
            title="Se déconnecter"
            aria-label="Se déconnecter"
          >
            <LogOut className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
    </header>
  );
}
