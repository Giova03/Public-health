/**
 * POST /api/v1/appointments/{id}/{action} — confirmer | honorer | absent
 * | annuler (I10 : machine à états, passé immuable, motif obligatoire).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { decoderJeton, exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

const TRANSITIONS: Record<string, string[]> = {
  demande: ["confirme", "annule", "honore"],
  confirme: ["annule", "honore", "absent"],
  honore: [],
  annule: [],
  absent: [],
};

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string; action: string }> },
) {
  const { id, action } = await params;
  const annulation = action === "annuler";
  const jeton = decoderJeton(request.headers.get("authorization")?.replace(/^Bearer\s+/i, ""));
  if (!jeton) {
    return NextResponse.json(
      { title: "Authentification requise", detail: "Un jeton valide est obligatoire", status: 401 },
      { status: 401 },
    );
  }

  const state = getState();
  const rdv = state.appointments.find((r) => r.id === id);
  if (!rdv) {
    return NextResponse.json(
      { title: "Transition refusée", detail: "Rendez-vous introuvable", status: 404 },
      { status: 404 },
    );
  }

  // Annulation : le patient peut annuler SON rdv ; les autres transitions = staff.
  if (jeton.role === "patient") {
    if (!annulation || rdv.patientId !== jeton.patientId) {
      return NextResponse.json(
        { title: "Accès refusé", detail: "Un patient ne peut annuler que son propre rendez-vous", status: 403 },
        { status: 403 },
      );
    }
  } else {
    const garde = exigerPermission(request, "rendezvous:gerer");
    if (garde.refus) return garde.refus;
  }

  const cible = action === "confirmer" ? "confirme"
    : action === "honorer" ? "honore"
    : action === "absent" ? "absent"
    : "annule";
  if (!TRANSITIONS[rdv.statut]?.includes(cible)) {
    return NextResponse.json(
      {
        title: "Transition refusée",
        detail: `Transition illégale : ${rdv.statut} → ${cible} est interdit`,
        status: 409,
      },
      { status: 409 },
    );
  }
  const passe = new Date(rdv.creneau).getTime() < Date.now();
  // Un RDV passé est IMMUABLE (I10) — sauf consigner honoré/absent PAR UN AGENT.
  if (passe && (annulation || jeton.role === "patient" || (cible !== "honore" && cible !== "absent"))) {
    return NextResponse.json(
      { title: "Transition refusée", detail: "Rendez-vous passé : immuable (l'historique fait foi)", status: 409 },
      { status: 409 },
    );
  }

  let motifAnnulation: string | undefined;
  if (annulation) {
    const corps = await request.json().catch(() => null) as { motif?: string } | null;
    if (!corps?.motif?.trim()) {
      return NextResponse.json(
        { title: "Transition refusée", detail: "Le motif d'annulation est OBLIGATOIRE", status: 409 },
        { status: 409 },
      );
    }
    motifAnnulation = corps.motif.trim();
  }

  rdv.statut = cible;
  rdv.motifAnnulation = motifAnnulation;
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: jeton.sub,
    action: annulation ? "RDV_CANCELLED" : "RDV_TRANSITION",
    entite: "rendez_vous",
    entiteId: rdv.id,
    motif: motifAnnulation ?? `→${cible}`,
    resultat: "SUCCESS",
  });
  return NextResponse.json(rdv);
}
