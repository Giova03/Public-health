"use client";

import { useEffect } from "react";
import { Activity, HeartPulse } from "lucide-react";
import { AppShell } from "@/components/app-shell";
import { useAppStore } from "@/lib/store";
import { useSessionStore } from "@/lib/session";
import { LoginView } from "@/modules/auth/login-view";
import { PatientPortal } from "@/modules/patient-portal/view";

/**
 * PUBLIC HEALTH — application clinique PWA (mono-page : la plateforme de
 * démonstration n'expose que cette route).
 *
 * Trois états, pilotés par la session de démonstration (localStorage) :
 *  1. non connecté  → écran de connexion multi-profils (patient / staff) ;
 *  2. profil patient → portail patient (mes infos, ordonnances, paiements) ;
 *  3. profil staff   → poste de santé (AppShell : navigation filtrée par rôle).
 *
 * La navigation interne reste portée par l'état, le miroir IndexedDB et le
 * moteur de synchronisation.
 */
export default function Home() {
  const session = useSessionStore((s) => s.session);
  const sessionHydrated = useSessionStore((s) => s.hydrated);
  const hydrateSession = useSessionStore((s) => s.hydrateSession);
  const mirrorHydrated = useAppStore((s) => s.hydrated);
  const hydrateMirror = useAppStore((s) => s.hydrate);

  useEffect(() => {
    void hydrateSession();
    void hydrateMirror();
  }, [hydrateSession, hydrateMirror]);

  if (!sessionHydrated || !mirrorHydrated) {
    return (
      <div
        className="flex min-h-screen flex-col items-center justify-center gap-4 bg-background"
        role="status"
        aria-label="Ouverture de la plateforme"
      >
        <span
          className="flex h-14 w-14 items-center justify-center rounded-3xl bg-gradient-medical text-ink-medical shadow-float"
          aria-hidden="true"
        >
          <HeartPulse className="h-7 w-7" />
        </span>
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Activity className="h-4 w-4 animate-pulse" aria-hidden="true" />
          Ouverture du miroir local (IndexedDB)…
        </p>
      </div>
    );
  }

  if (!session) {
    return <LoginView />;
  }
  if (session.profile === "PATIENT") {
    return <PatientPortal />;
  }
  return <AppShell />;
}
