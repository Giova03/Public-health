"use client";

/**
 * PUBLIC HEALTH — session (V14 : connexion RÉELLE côté démo).
 *
 * Le staff se connecte par EMAIL + MOT DE PASSE (POST /api/v1/auth/login,
 * MFA incluse), le patient par TÉLÉPHONE + code OTP (2 appels) — plus
 * AUCUN « nom libre + rôle choisi dans une liste ». Le jeton démo est
 * stocké et envoyé en Authorization: Bearer ; les routes mock le
 * VÉRIFIENT (401/403) comme le backend vérifie le HS256.
 */

import { create } from "zustand";
import type { StaffRole } from "@/lib/types";
import { roleHasPermission, type PermissionCode } from "@/lib/rbac";

export type SessionProfile = "PATIENT" | "STAFF";

export interface StaffSession {
  profile: "STAFF";
  userId: string;
  email: string;
  fullName: string;
  role: StaffRole;
  facility: string;
  jeton: string;
  loggedAt: string;
}

export interface PatientSession {
  profile: "PATIENT";
  patientId: string;
  fullName: string;
  phone: string;
  facility: string;
  jeton: string;
  loggedAt: string;
}

export type AppSession = StaffSession | PatientSession;

const STORAGE_KEY = "ph.session.v2";

interface LoginStaffReponse {
  jeton: string;
  utilisateur: { id: string; email: string; role: StaffRole; nom: string; mfaActive: boolean };
}

interface SessionState {
  session: AppSession | null;
  hydrated: boolean;
  erreurAuth: string | null;
  hydrateSession: () => void;
  /** Login staff : email + mot de passe (+ code MFA au 2e appel). */
  loginStaff: (email: string, motDePasse: string, codeMfa?: string) => Promise<"OK" | "MFA" | "ERREUR">;
  /** Étape 1 patient : demande du code OTP (renvoyé en posture démo). */
  demanderOtpPatient: (telephone: string) => Promise<string | null>;
  /** Étape 2 patient : vérification → session. */
  loginPatient: (telephone: string, code: string) => Promise<boolean>;
  logout: () => void;
}

function persist(session: AppSession) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    /* mode privé : session en mémoire uniquement */
  }
}

async function postJson<T>(url: string, corps: unknown): Promise<{ status: number; body: T }> {
  const reponse = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(corps),
  });
  return { status: reponse.status, body: (await reponse.json().catch(() => null)) as T };
}

export const useSessionStore = create<SessionState>((set, get) => ({
  session: null,
  hydrated: false,
  erreurAuth: null,

  hydrateSession: () => {
    if (get().hydrated) return;
    let session: AppSession | null = null;
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      if (raw) session = JSON.parse(raw) as AppSession;
    } catch {
      session = null;
    }
    set({ session, hydrated: true });
  },

  loginStaff: async (email, motDePasse, codeMfa) => {
    const { status, body } = await postJson<LoginStaffReponse & { mfaRequise?: boolean; codeDemo?: string; detail?: string }>(
      "/api/v1/auth/login",
      { email, motDePasse, codeMfa },
    );
    if (status === 401 && body?.mfaRequise) {
      // Défi MFA : le code démo est porté par la réponse (posture démo).
      dernierDefiMfa = body.codeDemo ?? "";
      return "MFA";
    }
    if (status !== 200 || !body?.jeton) {
      set({ erreurAuth: body?.detail ?? "Email ou mot de passe incorrect" });
      return "ERREUR";
    }
    const session: StaffSession = {
      profile: "STAFF",
      userId: body.utilisateur.id,
      email: body.utilisateur.email,
      fullName: body.utilisateur.nom,
      role: body.utilisateur.role,
      facility: "CSPS Ouaga 12",
      jeton: body.jeton,
      loggedAt: new Date().toISOString(),
    };
    persist(session);
    set({ session, erreurAuth: null });
    return "OK";
  },

  demanderOtpPatient: async (telephone) => {
    const { status, body } = await postJson<{ message?: string; codeDemo?: string }>(
      "/api/v1/auth/patient/otp",
      { telephone },
    );
    if (status !== 202) return null;
    return body.codeDemo ?? null;
  },

  loginPatient: async (telephone, code) => {
    const { status, body } = await postJson<{ jeton?: string; patientId?: string; nom?: string }>(
      "/api/v1/auth/patient/verify",
      { telephone, code },
    );
    if (status !== 200 || !body?.jeton || !body.patientId) {
      set({ erreurAuth: "Code invalide ou expiré" });
      return false;
    }
    const session: PatientSession = {
      profile: "PATIENT",
      patientId: body.patientId,
      fullName: body.nom ?? "Patient",
      phone: telephone,
      facility: "—",
      jeton: body.jeton,
      loggedAt: new Date().toISOString(),
    };
    persist(session);
    set({ session, erreurAuth: null });
    return true;
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

/** Dernier défi MFA démo (code renvoyé par le serveur — posture démo). */
let dernierDefiMfa = "";

export function dernierCodeMfa(): string {
  return dernierDefiMfa;
}

/** Utilisateur démo par défaut (pré-rendu serveur, avant connexion). */
export const FALLBACK_USER = {
  fullName: "Non connecté",
  role: "INFIRMIER" as StaffRole,
  facility: "—",
};

/**
 * Identité du connecté pour les vues du poste — réactive à la session.
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

/** Le jeton courant (pour l'api-client). */
export function jetonCourant(): string | null {
  const session = useSessionStore.getState().session;
  return session?.jeton ?? null;
}

/** Garde d'action côté vues : le rôle du connecté porte-t-il la permission ? */
export function usePermission(permission: PermissionCode): boolean {
  const session = useSessionStore((s) => s.session);
  if (!session || session.profile !== "STAFF") return false;
  return roleHasPermission(session.role, permission);
}

export function permissionCourante(permission: PermissionCode): boolean {
  const session = useSessionStore.getState().session;
  if (!session || session.profile !== "STAFF") return false;
  return roleHasPermission(session.role, permission);
}

/**
 * Périmètre de vues : DÉDUIT des permissions (rbac.viewsForRole) —
 * l'ancienne table divergente est supprimée (I2 : nomenclatures alignées).
 */
export { viewsForRole as ROLE_VIEWS } from "@/lib/rbac";
