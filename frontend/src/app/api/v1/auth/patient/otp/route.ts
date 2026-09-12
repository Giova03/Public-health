import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";

/** POST /api/v1/auth/patient/otp — étape 1 : téléphone → code (I13/I27). */
export async function POST(requete: NextRequest) {
  const corps = await requete.json().catch(() => null) as { telephone?: string } | null;
  const chiffres = (corps?.telephone ?? "").replace(/\D/g, "");
  if (chiffres.length !== 8) {
    return NextResponse.json(
      { title: "Numéro invalide", detail: "Numéro de téléphone invalide (8 chiffres attendus)", status: 400 },
      { status: 400 },
    );
  }
  const state = getState();
  const patient = state.patients.find((p) => p.active && p.phone === chiffres);
  if (!patient) {
    // Message neutre : aucune divulgation d'existence.
    return NextResponse.json(
      { message: "Si ce numéro correspond à un dossier, un code vient d'être envoyé par SMS", codeDemo: "" },
      { status: 202 },
    );
  }
  const code = String(Math.floor(1000 + Math.random() * 9000));
  state.patientOtp.set(chiffres, code);
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: "anonyme",
    action: "AUTH_PATIENT_OTP",
    entite: "patient",
    entiteId: patient.id,
    motif: "demande de code",
    resultat: "SUCCESS",
  });
  // Posture démo : le code est renvoyé (aucune passerelle SMS livrée).
  return NextResponse.json(
    {
      message: "Code envoyé (démonstration : il est affiché ici, jamais en production)",
      codeDemo: code,
    },
    { status: 202 },
  );
}
