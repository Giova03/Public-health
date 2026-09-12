/**
 * POST /api/v1/examens/[id]/resultat — enregistrement du résultat (P1-8).
 *
 * Laboratoire:ecrire. Le résultat est forward-only : un examen déjà
 * résulté refuse (409) — la correction passe par une contre-demande,
 * jamais par une réécriture (append-only, même philosophie que V3).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import { getState } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

interface ResultatBody {
  resultatText: string;
  resultatPositif?: boolean;
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "laboratoire:ecrire");
  if (garde.refus) return garde.refus;
  const { id } = await params;

  const corps = await request.json().catch(() => null) as ResultatBody | null;
  const state = getState();
  const examen = state.examens.find((e) => e.id === id);
  if (!examen) {
    return NextResponse.json(
      { title: "Résultat refusé", detail: `Examen introuvable : ${id}`, status: 404 },
      { status: 404 },
    );
  }
  if (examen.statut === "resultat") {
    return NextResponse.json(
      {
        title: "Résultat refusé",
        detail: "Examen déjà résulté : le résultat est forward-only, la correction passe par une contre-demande",
        status: 409,
      },
      { status: 409 },
    );
  }
  if (!corps?.resultatText?.trim()) {
    return NextResponse.json(
      { title: "Résultat refusé", detail: "Le résultat est obligatoire", status: 400 },
      { status: 400 },
    );
  }

  examen.statut = "resultat";
  examen.resultatText = corps.resultatText.trim();
  examen.resultatPositif = corps.resultatPositif;
  examen.resultatLe = new Date().toISOString();

  // Miroir dans la consultation embarquée (l'historique du dossier).
  if (examen.consultationId) {
    const consultation = state.consultations.find((c) => c.id === examen.consultationId);
    const ligne = consultation?.examens.find((e) => e.id === examen.id);
    if (ligne) {
      ligne.statut = "resultat";
      ligne.resultat = examen.resultatText;
      ligne.positif = examen.resultatPositif;
    }
  }
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "EXAMEN_RESULTAT",
    entite: "examen",
    entiteId: examen.id,
    motif: `${examen.type} : ${examen.resultatText}`,
    resultat: "SUCCESS",
  });
  return NextResponse.json(examen);
}
