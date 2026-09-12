/**
 * GET  /api/v1/appointments?patientId=&statut= — RDV (I10).
 * POST /api/v1/appointments — création : agent (rendezvous:gerer) ou
 * patient (self-service, patient_id FORCÉ au claim, quota 1/jour).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { decoderJeton, exigerPermission } from "@/lib/demo/guard";
import type { AppointmentRecord, AppointmentType } from "@/lib/types";

export const dynamic = "force-dynamic";

const TRANSITIONS: Record<string, string[]> = {
  demande: ["confirme", "annule", "honore"],
  confirme: ["annule", "honore", "absent"],
  honore: [],
  annule: [],
  absent: [],
};

export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const patientId = params.get("patientId") ?? undefined;
  const statut = params.get("statut") ?? undefined;

  // Staff : rendezvous:gerer. Patient : SES demandes.
  const staff = exigerPermission(request, "rendezvous:gerer");
  let patientIdEffectif = patientId;
  if (staff.refus) {
    const jeton = decoderJeton(request.headers.get("authorization")?.replace(/^Bearer\s+/i, ""));
    if (!jeton) return staff.refus;
    if (jeton.role !== "patient") return staff.refus;
    patientIdEffectif = jeton.patientId;
  }
  const rdvs = getState().appointments
    .filter((r) => (!patientIdEffectif || r.patientId === patientIdEffectif)
      && (!statut || r.statut === statut))
    .sort((a, b) => b.creneau.localeCompare(a.creneau));
  return NextResponse.json({ appointments: rdvs }, { headers: { "cache-control": "no-store" } });
}

interface CorpsCreation {
  patientId?: string;
  structure?: string;
  type?: AppointmentType;
  creneau: string;
  motif?: string;
}

export async function POST(request: NextRequest) {
  const jeton = decoderJeton(request.headers.get("authorization")?.replace(/^Bearer\s+/i, ""));
  if (!jeton) {
    return NextResponse.json(
      { title: "Authentification requise", detail: "Un jeton valide est obligatoire", status: 401 },
      { status: 401 },
    );
  }
  const corps = await request.json().catch(() => null) as CorpsCreation | null;
  if (!corps?.creneau) {
    return NextResponse.json(
      { title: "Rendez-vous refusé", detail: "Le créneau est obligatoire", status: 400 },
      { status: 400 },
    );
  }
  let patientId: string;
  let demandePar: "patient" | "agent" = "agent";
  if (jeton.role === "patient") {
    patientId = jeton.patientId!;
    demandePar = "patient";
  } else {
    const garde = exigerPermission(request, "rendezvous:gerer");
    if (garde.refus) return garde.refus;
    patientId = corps.patientId!;
    if (!patientId) {
      return NextResponse.json(
        { title: "Rendez-vous refusé", detail: "Le patient est obligatoire", status: 400 },
        { status: 400 },
      );
    }
  }

  const state = getState();
  const patient = state.patients.find((p) => p.id === patientId && p.active);
  if (!patient) {
    return NextResponse.json(
      { title: "Rendez-vous refusé", detail: "Patient introuvable", status: 400 },
      { status: 400 },
    );
  }
  if (patient.deceased) {
    return NextResponse.json(
      { title: "Rendez-vous refusé", detail: "Dossier scellé : décès déclaré — aucun rendez-vous", status: 409 },
      { status: 409 },
    );
  }
  const creneau = new Date(corps.creneau);
  if (creneau.getTime() < Date.now()) {
    return NextResponse.json(
      { title: "Rendez-vous refusé", detail: "Le créneau doit être dans le FUTUR", status: 400 },
      { status: 400 },
    );
  }
  // Quota patient : 1 rendez-vous non annulé par jour (I10/N3-Q13).
  const jour = creneau.toISOString().slice(0, 10);
  const duJour = state.appointments.filter(
    (r) => r.patientId === patientId
      && r.statut !== "annule" && r.statut !== "absent"
      && r.creneau.slice(0, 10) === jour,
  );
  if (demandePar === "patient" && duJour.length >= 1) {
    return NextResponse.json(
      {
        title: "Rendez-vous refusé",
        detail: "Quota atteint : 1 rendez-vous par jour maximum (annulez le précédent)",
        status: 409,
      },
      { status: 409 },
    );
  }

  const rdv: AppointmentRecord = {
    id: `rdv-${Date.now().toString(36)}`,
    patientId,
    patientName: patient.name,
    structure: jeton.structure ?? corps.structure ?? "CSPS Ouaga 12",
    type: corps.type ?? "general",
    creneau: corps.creneau,
    statut: "demande",
    motif: corps.motif?.trim(),
    demandePar,
    createdAt: new Date().toISOString(),
  };
  state.appointments.unshift(rdv);
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: jeton.sub,
    action: "RDV_CREATED",
    entite: "rendez_vous",
    entiteId: rdv.id,
    motif: rdv.motif,
    resultat: "SUCCESS",
  });
  return NextResponse.json(rdv, { status: 201 });
}

/** Transitions légales exportées pour la route [id]. */
export { TRANSITIONS as RDV_TRANSITIONS };
