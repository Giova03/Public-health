/**
 * GET  /api/v1/examens — par patient / par statut (consultation:lire).
 * POST /api/v1/examens — commande d'examen (P1-8, laboratoire:ecrire).
 *
 * Miroir du backend ExamenController : la preuve derrière le diagnostic.
 * Le patient (jeton autoporteur) lit SES résultats — périmètre strict.
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission, exigerPerimetrePatient } from "@/lib/demo/guard";
import type { ExamenLaboRecord } from "@/lib/types";
import { TYPES_EXAMENS } from "@/lib/types";
import { getState, newEntityId } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const patientId = request.nextUrl.searchParams.get("patientId");
  const statut = request.nextUrl.searchParams.get("statut");
  // Staff : consultation:lire. Patient : SES examens (périmètre).
  const staff = exigerPermission(request, "consultation:lire");
  if (staff.refus) {
    const patient = exigerPerimetrePatient(request, patientId);
    if (patient.refus) return patient.refus;
  }
  let examens = getState().examens;
  if (patientId) examens = examens.filter((e) => e.patientId === patientId);
  if (statut) examens = examens.filter((e) => e.statut === statut);
  const tri = [...examens].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 100);
  return NextResponse.json(
    { examens: tri },
    { headers: { "cache-control": "no-store" } },
  );
}

interface CreationBody {
  patientId: string;
  consultationId?: string;
  type: string;
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "laboratoire:ecrire");
  if (garde.refus) return garde.refus;

  const corps = await request.json().catch(() => null) as CreationBody | null;
  if (!corps?.patientId || !corps.type?.trim()) {
    return NextResponse.json(
      { title: "Examen refusé", detail: "Le patient et le type d'examen sont obligatoires", status: 400 },
      { status: 400 },
    );
  }
  if (!TYPES_EXAMENS.some((t) => t.value === corps.type)) {
    return NextResponse.json(
      { title: "Examen refusé", detail: `Type d'examen inconnu : ${corps.type}`, status: 400 },
      { status: 400 },
    );
  }
  const state = getState();
  const patient = state.patients.find((p) => p.id === corps.patientId && p.active);
  if (!patient) {
    return NextResponse.json(
      { title: "Examen refusé", detail: `Patient introuvable : ${corps.patientId}`, status: 400 },
      { status: 400 },
    );
  }

  const examen: ExamenLaboRecord = {
    id: `e-${newEntityId().slice(0, 8)}`,
    patientId: corps.patientId,
    consultationId: corps.consultationId,
    structure: garde.token.structure ?? "CSPS Ouaga 12",
    type: corps.type,
    statut: "commande",
    demandePar: garde.token.sub,
    createdAt: new Date().toISOString(),
  };
  state.examens.unshift(examen);
  // L'examen embarque aussi dans la consultation (l'historique du dossier).
  if (corps.consultationId) {
    const consultation = state.consultations.find((c) => c.id === corps.consultationId);
    if (consultation) {
      consultation.examens.push({
        id: examen.id, type: examen.type, statut: "commande",
      });
    }
  }
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "EXAMEN_CREATED",
    entite: "examen",
    entiteId: examen.id,
    motif: examen.type,
    resultat: "SUCCESS",
  });
  return NextResponse.json(examen, { status: 201 });
}
