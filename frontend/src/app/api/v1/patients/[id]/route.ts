/**
 * GET /api/v1/patients/[id] — fiche patient ; 410 Gone + masterId si fusion.
 */

import { NextResponse } from "next/server";
import { findPatient } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const patient = findPatient(id);

  if (!patient) {
    return NextResponse.json(
      { title: "Dossier introuvable", status: 404 },
      { status: 404 },
    );
  }

  if (!patient.active && patient.masterId) {
    // Contrat E1 : dossier fusionné → redirection MPI, jamais un 404 muet.
    return NextResponse.json(
      {
        title: "Dossier fusionné",
        detail:
          "Ce dossier a été fusionné dans le dossier maître. La consultation doit poursuivre sur le maître.",
        status: 410,
        masterId: patient.masterId,
      },
      { status: 410 },
    );
  }

  return NextResponse.json(
    { patient },
    { headers: { "cache-control": "no-store" } },
  );
}
