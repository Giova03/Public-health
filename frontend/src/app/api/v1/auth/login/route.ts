import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { encoderDefi, encoderJeton, motDePasseDemoValide, verifierDefi } from "@/lib/demo/guard";

/**
 * POST /api/v1/auth/login — authentification staff (V14, I3).
 * BCrypt côté backend ; ici miroir démo avec vérification réelle du
 * mot de passe commun + MFA (défi 401 avec code renvoyé — posture démo
 * documentée : en production, le module notification envoie le code).
 * Le défi MFA est SANS ÉTAT (jeton « defi. ») : l’émission et la
 * vérification peuvent toucher deux lambdas Vercel différents.
 */
export async function POST(requete: NextRequest) {
  const corps = await requete.json().catch(() => null) as {
    email?: string;
    motDePasse?: string;
    codeMfa?: string;
    defi?: string;
  } | null;
  if (!corps?.email || !corps?.motDePasse) {
    return NextResponse.json(
      { title: "Requête incomplète", detail: "Email et mot de passe obligatoires", status: 400 },
      { status: 400 },
    );
  }
  const email = corps.email.trim().toLowerCase();
  const state = getState();
  const utilisateur = state.users.find((u) => (u.email ?? "").toLowerCase() === email);

  // Message neutre : jamais dire si l'email existe (anti-énumération).
  if (!utilisateur || !motDePasseDemoValide(corps.motDePasse) || !utilisateur.active) {
    state.auditLog.unshift({
      date: new Date().toISOString(),
      acteur: "anonyme",
      action: "AUTH_LOGIN",
      entite: "utilisateur",
      motif: "identifiants invalides",
      resultat: "FAILURE",
    });
    return NextResponse.json(
      { title: "Authentification refusée", detail: "Email ou mot de passe incorrect", status: 401 },
      { status: 401 },
    );
  }

  // MFA : défi 2 étapes.
  if (utilisateur.mfaEnabled && !corps.codeMfa) {
    const code = String(Math.floor(100000 + Math.random() * 900000));
    state.mfaChallenges.set(email, code);
    return NextResponse.json(
      {
        mfaRequise: true,
        email,
        detail: `Code MFA requis — un code à 6 chiffres vient d'être généré (démonstration : ${code})`,
        codeDemo: code,
        defi: encoderDefi(email, code),
        status: 401,
      },
      { status: 401 },
    );
  }
  if (utilisateur.mfaEnabled) {
    if (!verifierDefi(corps.defi, email, corps.codeMfa, state.mfaChallenges)) {
      return NextResponse.json(
        { title: "Authentification refusée", detail: "Code MFA invalide ou expiré", status: 401 },
        { status: 401 },
      );
    }
  }

  utilisateur.lastSeenAt = new Date().toISOString();
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: utilisateur.id,
    action: "AUTH_LOGIN",
    entite: "utilisateur",
    entiteId: utilisateur.id,
    motif: "connexion interne (démo)",
    resultat: "SUCCESS",
  });

  const jeton = encoderJeton({
    sub: utilisateur.id,
    role: utilisateur.role,
    nom: utilisateur.fullName,
    structure: utilisateur.facility,
  });
  return NextResponse.json({
    jeton,
    expireALe: Math.floor(Date.now() / 1000) + 12 * 3600,
    utilisateur: {
      id: utilisateur.id,
      email: utilisateur.email,
      role: utilisateur.role,
      structureId: "",
      nom: utilisateur.fullName,
      mfaActive: utilisateur.mfaEnabled,
    },
  });
}
