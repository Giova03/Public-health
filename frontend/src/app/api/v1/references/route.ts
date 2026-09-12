/**
 * GET  /api/v1/references?statut= — références / contre-références (I7).
 * POST /api/v1/references — initier une référence (motif OBLIGATOIRE).
 * POST /api/v1/references/{id}/{action} — reception | hospitalisation |
 * contre-reference (résumé OBLIGATOIRE : la boucle se referme).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";
import type { ReferenceFiche } from "@/lib/types";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "reference:gerer");
  if (garde.refus) return garde.refus;
  const statut = request.nextUrl.searchParams.get("statut") ?? undefined;
  const references = getState().references
    .filter((r) => !statut || r.statut === statut)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return NextResponse.json({ references }, { headers: { "cache-control": "no-store" } });
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "reference:gerer");
  if (garde.refus) return garde.refus;
  const corps = await request.json().catch(() => null) as {
    patientId?: string;
    structureDestination?: string;
    motif?: string;
    urgence?: boolean;
  } | null;
  if (!corps?.patientId || !corps.structureDestination || !corps.motif?.trim()) {
    return NextResponse.json(
      {
        title: "Référence refusée",
        detail: "Patient, structure de destination et motif obligatoires",
        status: 400,
      },
      { status: 400 },
    );
  }
  const state = getState();
  const patient = state.patients.find((p) => p.id === corps.patientId);
  if (!patient) {
    return NextResponse.json(
      { title: "Référence refusée", detail: "Patient introuvable", status: 400 },
      { status: 400 },
    );
  }
  const origine = garde.token.structure ?? "CSPS Ouaga 12";
  if (origine === corps.structureDestination) {
    return NextResponse.json(
      { title: "Référence refusée", detail: "La référence doit changer de structure (origine ≠ destination)", status: 400 },
      { status: 400 },
    );
  }
  const fiche: ReferenceFiche = {
    id: `ref-${Date.now().toString(36)}`,
    patientId: patient.id,
    patientName: patient.name,
    structureOrigine: origine,
    structureDestination: corps.structureDestination,
    motif: corps.motif.trim(),
    urgence: corps.urgence ?? false,
    statut: "envoyee",
    createdAt: new Date().toISOString(),
  };
  state.references.unshift(fiche);
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "REFERENCE_CREATED",
    entite: "reference",
    entiteId: fiche.id,
    motif: fiche.motif,
    resultat: "SUCCESS",
  });
  return NextResponse.json(fiche, { status: 201 });
}
