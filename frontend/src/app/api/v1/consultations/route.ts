/**
 * GET  /api/v1/consultations?patientId= — dossier clinique (I4).
 * POST /api/v1/consultations — l'ACTE CLINIQUE COMPLET persisté :
 * motif, constantes, notes, diagnostic CODÉ (I14).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState, ticketDuJour } from "@/lib/demo/seed";
import { exigerPermission, exigerPerimetrePatient } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const patientId = request.nextUrl.searchParams.get("patientId");
  // Staff : consultation:lire. Patient : SES consultations (périmètre).
  const staff = exigerPermission(request, "consultation:lire");
  if (staff.refus) {
    const patient = exigerPerimetrePatient(request, patientId);
    if (patient.refus) return patient.refus;
  }
  const consultations = getState().consultations
    .filter((c) => !patientId || c.patientId === patientId)
    .sort((a, b) => b.date.localeCompare(a.date));
  return NextResponse.json({ consultations }, { headers: { "cache-control": "no-store" } });
}

interface CorpsConsultation {
  patientId: string;
  motif: string;
  diagnosticCode: string;
  diagnosticLabel?: string;
  notes?: string;
  constantes?: {
    taSystolique?: number;
    taDiastolique?: number;
    temperatureC?: number;
    poidsKg?: number;
  };
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "consultation:ecrire");
  if (garde.refus) return garde.refus;

  const corps = await request.json().catch(() => null) as CorpsConsultation | null;
  if (!corps?.patientId || !corps.motif?.trim() || !corps.diagnosticCode?.trim()) {
    return NextResponse.json(
      { title: "Consultation impossible", detail: "Patient, motif et diagnostic codé obligatoires", status: 400 },
      { status: 400 },
    );
  }
  const state = getState();
  const patient = state.patients.find((p) => p.id === corps.patientId && p.active);
  if (!patient) {
    return NextResponse.json(
      { title: "Consultation impossible", detail: "Patient introuvable", status: 400 },
      { status: 400 },
    );
  }
  if (patient.deceased) {
    return NextResponse.json(
      { title: "Consultation impossible", detail: "Dossier scellé : décès déclaré — aucune consultation possible", status: 409 },
      { status: 409 },
    );
  }

  // I5 — le parcours monétaire réel : ticket d'accès du jour réglé AVANT
  // l'acte clinique (payé ou exonéré à la caisse), sinon 402 Payment
  // Required avec etape=caisse (le clinicien est renvoyé à la caisse).
  const structure = garde.token.structure ?? "CSPS Ouaga 12";
  const ticket = ticketDuJour(corps.patientId, structure);
  if (!ticket || ticket.statut === "en_attente") {
    return NextResponse.json(
      {
        title: "Frais d'accès requis",
        detail: ticket
          ? "Ticket d'accès EN ATTENTE à la caisse : encaissez ou exonérez avant la consultation"
          : `Aucun ticket d'accès pour aujourd'hui : passage à la caisse obligatoire avant la consultation (${structure})`,
        etape: "caisse",
        status: 402,
      },
      { status: 402 },
    );
  }

  const consultation = {
    id: `c-${String(state.consultations.length + 1).padStart(3, "0")}-${Date.now().toString(36)}`,
    patientId: corps.patientId,
    facility: garde.token.structure ?? "CSPS Ouaga 12",
    practitioner: garde.token.nom,
    motif: corps.motif.trim(),
    diagnosticCode: corps.diagnosticCode.trim(),
    diagnosticLabel: corps.diagnosticLabel?.trim() ?? corps.diagnosticCode.trim(),
    notes: corps.notes?.trim() || undefined,
    constantes: corps.constantes ?? {},
    examens: [],
    date: new Date().toISOString(),
  };
  state.consultations.unshift(consultation);
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "CONSULTATION_CREATED",
    entite: "consultation",
    entiteId: consultation.id,
    motif: consultation.diagnosticCode,
    resultat: "SUCCESS",
  });
  return NextResponse.json(consultation, { status: 201 });
}
