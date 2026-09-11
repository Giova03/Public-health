"use client";

/**
 * PUBLIC HEALTH — session de démonstration (client uniquement).
 *
 * En production, l'authentification est portée par le module sécurité du
 * backend (OIDC/Keycloak : comptes personnel avec MFA pour les rôles
 * sensibles, patients via téléphone + code SMS à usage unique — voir
 * ARCHITECTURE.md). Cette session démo simule l'expérience complète côté
 * front : profil Patient (téléphone + OTP) ou profil Personnel de santé
 * (identité + rôle + structure). Persistée dans localStorage — aucune
 * donnée de santé réelle ne transite ici.
 */

import { create } from "zustand";
import type { StaffRole, ViewId } from "@/lib/types";

export type SessionProfile = "PATIENT" | "STAFF";

export interface StaffSession {
  profile: "STAFF";
  fullName: string;
  role: StaffRole;
  facility: string;
  loggedAt: string;
}

export interface PatientSession {
  profile: "PATIENT";
  patientId: string;
  fullName: string;
  phone: string;
  facility: string;
  loggedAt: string;
}

export type DemoSession = StaffSession | PatientSession;

const STORAGE_KEY = "ph.demo.session.v1";

interface SessionState {
  session: DemoSession | null;
  /** Vrai après lecture de localStorage (évite le flash « non connecté »). */
  hydrated: boolean;
  hydrateSession: () => void;
  loginStaff: (input: {
    fullName: string;
    role: StaffRole;
    facility: string;
  }) => void;
  loginPatient: (input: {
    patientId: string;
    fullName: string;
    phone: string;
    facility: string;
  }) => void;
  logout: () => void;
}

function persist(session: DemoSession) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    /* mode privé navigateur : session en mémoire uniquement */
  }
}

export const useSessionStore = create<SessionState>((set, get) => ({
  session: null,
  hydrated: false,
  hydrateSession: () => {
    if (get().hydrated) return;
    let session: DemoSession | null = null;
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      if (raw) session = JSON.parse(raw) as DemoSession;
    } catch {
      session = null;
    }
    set({ session, hydrated: true });
  },
  loginStaff: (input) => {
    const session: StaffSession = {
      profile: "STAFF",
      ...input,
      loggedAt: new Date().toISOString(),
    };
    persist(session);
    set({ session });
  },
  loginPatient: (input) => {
    const session: PatientSession = {
      profile: "PATIENT",
      ...input,
      loggedAt: new Date().toISOString(),
    };
    persist(session);
    set({ session });
  },
  logout: () => {
    try {
      window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* noop */
    }
    set({ session: null });
  },
}));

/** Utilisateur démo par défaut (pré-rendu serveur, avant connexion). */
export const FALLBACK_USER = {
  fullName: "Aminata Sawadogo",
  role: "INFIRMIER" as StaffRole,
  facility: "CSPS Ouaga 12",
};

/**
 * Identité du connecté pour les vues du poste — réactive à la session.
 * Sans session staff (pré-rendu, démo hors connexion), retombe sur
 * l'utilisateur démo historique.
 */
export function useCurrentUser() {
  const session = useSessionStore((s) => s.session);
  if (session && session.profile === "STAFF") {
    return {
      fullName: session.fullName,
      role: session.role,
      facility: session.facility,
    };
  }
  return FALLBACK_USER;
}

/**
 * Périmètre de vues par rôle : chaque intervenant du parcours de soins ne
 * voit que ses modules. Le tableau de bord et la synchronisation sont
 * communs à tous (mêmes données miroir, même outbox).
 */
export const ROLE_VIEWS: Record<StaffRole, ViewId[]> = {
  AGENT_SAISIE: ["dashboard", "patients", "sync"],
  INFIRMIER: ["dashboard", "patients", "consultation", "sync"],
  MEDECIN: ["dashboard", "patients", "consultation", "prescriptions", "sync"],
  PHARMACIEN: ["dashboard", "prescriptions", "sync"],
  CAISSIER: ["dashboard", "patients", "payments", "sync"],
  SUPERVISEUR: [
    "dashboard",
    "patients",
    "prescriptions",
    "payments",
    "sync",
    "backoffice",
  ],
  ADMIN: [
    "dashboard",
    "patients",
    "consultation",
    "prescriptions",
    "payments",
    "sync",
    "backoffice",
  ],
};
