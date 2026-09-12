/**
 * POST /api/v1/frais-acces/[id]/exonerer — exonération TRACÉE (I5).
 *
 * La gratuité est la NORME pour l'indigent attesté, l'enfant de moins de
 * 5 ans, la césarienne, la grossesse suivie — le système la trace au lieu
 * de l'ignorer : nature CONNUE + motif OBLIGATOIRE, forward-only (409).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import { EXONERATION_NATURES, type ExonerationNature } from "@/lib/types";
import { findTicket, getState } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

interface ExonerationBody {
  nature: ExonerationNature;
  motif: string;
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "paiement:initier");
  if (garde.refus) return garde.refus;
  const { id } = await params;

  const corps = await request.json().catch(() => null) as ExonerationBody | null;
  const ticket = findTicket(id);
  if (!ticket) {
    return NextResponse.json(
      { title: "Exonération impossible", detail: `Ticket introuvable : ${id}`, status: 404 },
      { status: 404 },
    );
  }
  if (ticket.statut !== "en_attente") {
    return NextResponse.json(
      {
        title: "Exonération impossible",
        detail: `Ticket déjà réglé (${ticket.statut}) : en_attente → exonere est forward-only`,
        status: 409,
      },
      { status: 409 },
    );
  }
  const natures = EXONERATION_NATURES.map((n) => n.value).join(", ");
  if (!corps?.nature || !EXONERATION_NATURES.some((n) => n.value === corps.nature)) {
    return NextResponse.json(
      { title: "Exonération impossible", detail: `Nature d'exonération inconnue (attendu parmi ${natures})`, status: 400 },
      { status: 400 },
    );
  }
  if (!corps.motif?.trim()) {
    return NextResponse.json(
      { title: "Exonération impossible", detail: "Le motif d'exonération est OBLIGATOIRE (traçabilité — I5)", status: 400 },
      { status: 400 },
    );
  }

  ticket.statut = "exonere";
  ticket.exonerationNature = corps.nature;
  ticket.exonerationMotif = corps.motif.trim();
  ticket.exonerationDecideePar = garde.token.sub;

  getState().auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "FRAIS_ACCES_EXONERE",
    entite: "frais_acces",
    entiteId: ticket.id,
    motif: `exonération ${corps.nature} : ${corps.motif.trim()}`,
    resultat: "SUCCESS",
  });
  return NextResponse.json(ticket);
}
