/**
 * Garde d'authentification et de permissions pour les routes mock /api/v1.
 *
 * Miroir honnête du backend V14 : un jeton est exigé (401 problem+json),
 * puis la permission de la route est vérifiée dans la matrice RBAC
 * (403 + entrée PERMISSION_DENIED dans l'audit démo). Les jetons démo
 * sont des payloads signés « à la mode JWT » (base64url, validation
 * d'expiration) — le vrai HS256 vit côté backend.
 */

import { NextRequest, NextResponse } from "next/server";
import { roleHasPermission, type PermissionCode } from "@/lib/rbac";
import type { StaffRole } from "@/lib/types";
import { getState } from "@/lib/demo/seed";

export interface DemoToken {
  sub: string;
  role: StaffRole | "patient";
  nom: string;
  structure?: string;
  patientId?: string;
  exp: number; // epoch secondes
}

const MOT_DE_PASSE_DEMO = "Demo1234!";

export function motDePasseDemoValide(motDePasse: string): boolean {
  return motDePasse === MOT_DE_PASSE_DEMO;
}

export function encoderJeton(payload: Omit<DemoToken, "exp">): string {
  const exp = Math.floor(Date.now() / 1000) + 12 * 3600;
  const corps = Buffer.from(JSON.stringify({ ...payload, exp })).toString("base64url");
  return `demo.${corps}`;
}

export function decoderJeton(jeton: string | null | undefined): DemoToken | null {
  if (!jeton || !jeton.startsWith("demo.")) return null;
  try {
    const payload = JSON.parse(Buffer.from(jeton.slice(5), "base64url").toString()) as DemoToken;
    if (payload.exp * 1000 < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

/* ------------------------------------------------------------------ */
/* Défis d'authentification SANS ÉTAT (serverless-safe).              */
/* ------------------------------------------------------------------ */

export interface DefiAuth {
  /** email (MFA staff) ou téléphone 8 chiffres (OTP patient). */
  cible: string;
  code: string;
  exp: number; // epoch secondes
}

/**
 * Encode un défi MFA/OTP en jeton « defi.<base64url> ».
 *
 * Vercel exécute les routes sur des lambdas qui se réchauffent/se
 * recréent indépendamment : un code stocké dans la Map mémoire du
 * serveur démo peut être ÉMIS par l'instance A puis VÉRIFIÉ par
 * l'instance B → « Code invalide ou expiré » systématique (3e échec
 * de déploiement : tout fonctionnait en local, plus rien en ligne).
 * Le défi voyage donc avec le client et revient à la vérification.
 * Posture démo inchangée : le code est de toute façon affiché
 * (codeDemo) — en production le module notification garde l'état.
 */
export function encoderDefi(cible: string, code: string, dureeSecondes = 300): string {
  const exp = Math.floor(Date.now() / 1000) + dureeSecondes;
  return `defi.${Buffer.from(JSON.stringify({ cible, code, exp })).toString("base64url")}`;
}

/** Décode un jeton de défi (null si malformé ou expiré). */
export function decoderDefi(jeton: string | null | undefined): DefiAuth | null {
  if (!jeton || !jeton.startsWith("defi.")) return null;
  try {
    const d = JSON.parse(Buffer.from(jeton.slice(5), "base64url").toString()) as DefiAuth;
    if (typeof d.cible !== "string" || typeof d.code !== "string" || typeof d.exp !== "number") {
      return null;
    }
    if (d.exp * 1000 < Date.now()) return null;
    return d;
  } catch {
    return null;
  }
}

/**
 * Vérifie un code MFA/OTP : d'abord par jeton de défi (sans état,
 * robuste sur serverless), puis par Map mémoire (back-compat).
 * Consomme le défi en cas de succès (usage unique).
 */
export function verifierDefi(
  defi: string | null | undefined,
  cible: string,
  code: string | null | undefined,
  memoire: Map<string, string>,
): boolean {
  const codePropose = code?.trim() ?? "";
  if (!codePropose) return false;
  const d = decoderDefi(defi);
  if (d && d.cible === cible && d.code === codePropose) {
    memoire.delete(cible);
    return true;
  }
  const attendu = memoire.get(cible);
  if (attendu === codePropose) {
    memoire.delete(cible);
    return true;
  }
  return false;
}

function problem(status: number, title: string, detail: string) {
  return NextResponse.json(
    { type: "about:blank", title, detail, status },
    { status, headers: { "content-type": "application/problem+json" } },
  );
}

/**
 * Exige un jeton valide + la permission. Retourne soit une réponse
 * 401/403 (à renvoyer immédiatement), soit le contexte du jeton.
 */
export function exigerPermission(
  requete: NextRequest,
  permission: PermissionCode,
): { refus: NextResponse } | { token: DemoToken; refus: null } {
  const jeton = decoderJeton(requete.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? null);
  if (!jeton) {
    return { refus: problem(401, "Authentification requise", "Un jeton valide (Authorization: Bearer) est obligatoire") };
  }
  if (jeton.role === "patient") {
    return { refus: problem(403, "Accès refusé", "Un patient n'accède qu'à ses propres données") };
  }
  if (!roleHasPermission(jeton.role, permission)) {
    const state = getState();
    state.auditLog.unshift({
      date: new Date().toISOString(),
      acteur: jeton.sub,
      action: "PERMISSION_DENIED",
      entite: "api",
      motif: `${requete.method} ${requete.nextUrl.pathname} : la permission ${permission} est requise (rôle ${jeton.role})`,
      resultat: "DENIED",
    });
    return {
      refus: problem(
        403,
        "Accès refusé",
        `La permission ${permission} est requise (rôle ${jeton.role})`,
      ),
    };
  }
  return { token: jeton, refus: null };
}

/**
 * Périmètre patient : jeton patient + patientId demandé == claim.
 * Retourne le refus (401/403) ou le jeton validé.
 */
export function exigerPerimetrePatient(
  requete: NextRequest,
  patientId: string | null | undefined,
): { refus: NextResponse } | { token: DemoToken; refus: null } {
  const jeton = decoderJeton(requete.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? null);
  if (!jeton) {
    return { refus: problem(401, "Authentification requise", "Un jeton valide (Authorization: Bearer) est obligatoire") };
  }
  if (jeton.role !== "patient") return { token: jeton, refus: null };
  if (!patientId || patientId !== jeton.patientId) {
    return { refus: problem(403, "Accès refusé", "Un patient n'accède qu'à ses propres données") };
  }
  return { token: jeton, refus: null };
}

/** Variante : accepter plusieurs permissions (lecture back-office). */
export function exigerPermissionParmi(
  requete: NextRequest,
  permissions: PermissionCode[],
): { refus: NextResponse } | { token: DemoToken; refus: null } {
  for (const permission of permissions) {
    const garde = exigerPermission(requete, permission);
    if (!garde.refus) return garde;
  }
  // Refus du dernier essai (401 si anonyme, 403 avec la dernière permission).
  return exigerPermission(requete, permissions[permissions.length - 1]);
}
