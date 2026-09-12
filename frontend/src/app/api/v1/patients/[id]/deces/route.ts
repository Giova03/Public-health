/**
 * POST /api/v1/patients/{id}/deces — déclaration de décès (I15) :
 * dossier scellé (plus aucune consultation ni RDV), cause en audit.
 * Permission : consultation:ecrire (médecin, infirmier ICP, admin).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "consultation:ecrire");
  if (garde.refus) return garde.refus;
  const { id } = await params;
  const corps = await request.json().catch(() => null) as { cause?: string } | null;
  if (!corps?.cause?.trim()) {
    return NextResponse.json(
      { title: "Décès refusé", detail: "La cause du décès est obligatoire", status: 400 },
      { status: 400 },
    );
  }
  const state = getState();
  const patient = state.patients.find((p) => p.id === id);
  if (!patient) {
    return NextResponse.json(
      { title: "Patient introuvable", detail: "Patient introuvable", status: 404 },
      { status: 404 },
    );
  }
  if (patient.deceased) {
    return NextResponse.json(
      { title: "Décès déjà déclaré", detail: "Décès déjà déclaré", status: 409 },
      { status: 409 },
    );
  }
  patient.deceased = true;
  patient.deceasedAt = new Date().toISOString();
  patient.causeDeces = corps.cause.trim();
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "PATIENT_DEATH",
    entite: "patient",
    entiteId: patient.id,
    motif: corps.cause.trim(),
    resultat: "SUCCESS",
  });
  return NextResponse.json({ ok: true, patientId: patient.id });
}
