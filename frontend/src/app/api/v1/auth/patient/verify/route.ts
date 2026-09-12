import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { encoderJeton } from "@/lib/demo/guard";

/** POST /api/v1/auth/patient/verify — étape 2 : code → jeton patient. */
export async function POST(requete: NextRequest) {
  const corps = await requete.json().catch(() => null) as { telephone?: string; code?: string } | null;
  const chiffres = (corps?.telephone ?? "").replace(/\D/g, "");
  const state = getState();
  const attendu = state.patientOtp.get(chiffres);
  if (!attendu || attendu !== corps?.code?.trim()) {
    return NextResponse.json(
      { title: "Code refusé", detail: "Code invalide ou expiré", status: 401 },
      { status: 401 },
    );
  }
  state.patientOtp.delete(chiffres);
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
