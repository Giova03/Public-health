import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { encoderJeton, verifierDefi } from "@/lib/demo/guard";

/** POST /api/v1/auth/patient/verify — étape 2 : code → jeton patient.
 * Vérification SANS ÉTAT d'abord (jeton « defi. », serverless-safe),
 * puis retour sur la Map mémoire (back-compat). */
export async function POST(requete: NextRequest) {
  const corps = await requete.json().catch(() => null) as {
    telephone?: string;
    code?: string;
    defi?: string;
  } | null;
  const chiffres = (corps?.telephone ?? "").replace(/\D/g, "");
  const state = getState();
  if (!verifierDefi(corps?.defi, chiffres, corps?.code, state.patientOtp)) {
    return NextResponse.json(
      { title: "Code refusé", detail: "Code invalide ou expiré", status: 401 },
      { status: 401 },
    );
  }
  const patient = state.patients.find((p) => p.active && p.phone === chiffres);
  if (!patient) {
    return NextResponse.json(
      { title: "Code refusé", detail: "Aucun dossier lié à ce numéro", status: 401 },
      { status: 401 },
    );
  }
  const nom = `${patient.name.given} ${patient.name.family}`;
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: patient.id,
    action: "AUTH_PATIENT_LOGIN",
    entite: "patient",
    entiteId: patient.id,
    motif: "connexion patient par OTP",
    resultat: "SUCCESS",
  });
  const jeton = encoderJeton({ sub: patient.id, role: "patient", nom, patientId: patient.id });
  return NextResponse.json({
    jeton,
    expireALe: Math.floor(Date.now() / 1000) + 12 * 3600,
    patientId: patient.id,
    nom,
  });
}
