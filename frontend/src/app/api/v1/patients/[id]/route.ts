/**
 * GET /api/v1/patients/[id] — fiche patient ; 410 Gone + masterId si fusion.
 */

import { NextRequest, NextResponse } from "next/server";
import { findPatient } from "@/lib/demo/seed";
import { decoderJeton, exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  // V14 (I1) : jeton exigé — staff avec patient:lire, ou le patient
  // LUI-MÊME (périmètre strict : id == claim patient_id).
  const jeton = decoderJeton(request.headers.get("authorization")?.replace(/^Bearer\s+/i, ""));
  if (!jeton) {
    return NextResponse.json(
      { title: "Authentification requise", detail: "Un jeton valide est obligatoire", status: 401 },
      { status: 401 },
    );
  }
  if (jeton.role === "patient") {
    if (jeton.patientId !== (await params).id) {
      return NextResponse.json(
        { title: "Accès refusé", detail: "Un patient n'accède qu'à ses propres données", status: 403 },
        { status: 403 },
      );
    }
  } else {
    const garde = exigerPermission(request, "patient:lire");
    if (garde.refus) return garde.refus;
  }
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
