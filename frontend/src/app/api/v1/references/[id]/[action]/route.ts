/**
 * POST /api/v1/references/{id}/{action} — reception | hospitalisation |
 * contre-reference (I7 : la boucle se referme, résumé OBLIGATOIRE).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string; action: string }> },
) {
  const garde = exigerPermission(request, "reference:gerer");
  if (garde.refus) return garde.refus;
  const { id, action } = await params;
  const state = getState();
  const fiche = state.references.find((r) => r.id === id);
  if (!fiche) {
    return NextResponse.json(
      { title: "Transition refusée", detail: "Référence introuvable", status: 404 },
      { status: 404 },
    );
  }
  if (action === "reception" || action === "hospitalisation") {
    const cible = action === "reception" ? "recue" : "hospitalisee";
    const attendu = action === "reception" ? "envoyee" : "recue";
    if (fiche.statut !== attendu) {
      return NextResponse.json(
        {
          title: "Transition refusée",
          detail: `Transition illégale : ${fiche.statut} → ${cible}`,
          status: 409,
        },
        { status: 409 },
      );
    }
    fiche.statut = cible;
    if (cible === "recue") fiche.recueLe = new Date().toISOString();
  } else if (action === "contre-reference") {
    if (fiche.statut !== "recue" && fiche.statut !== "hospitalisee") {
      return NextResponse.json(
        {
          title: "Contre-référence refusée",
          detail: `Impossible depuis le statut ${fiche.statut}`,
          status: 409,
        },
        { status: 409 },
      );
    }
    const corps = await request.json().catch(() => null) as { resume?: string } | null;
    if (!corps?.resume?.trim()) {
      return NextResponse.json(
        {
          title: "Contre-référence refusée",
          detail: "Le résumé de contre-référence est OBLIGATOIRE (la boucle se referme)",
          status: 409,
        },
        { status: 409 },
      );
    }
    fiche.contreReference = corps.resume.trim();
    fiche.statut = "retournee";
  } else {
    return NextResponse.json(
      { title: "Transition refusée", detail: `Action inconnue : ${action}`, status: 400 },
      { status: 400 },
    );
  }
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: action === "contre-reference" ? "CONTRE_REFERENCE" : `REFERENCE_${action.toUpperCase()}`,
    entite: "reference",
    entiteId: fiche.id,
    motif: fiche.contreReference ?? fiche.statut,
    resultat: "SUCCESS",
  });
  return NextResponse.json(fiche);
}
