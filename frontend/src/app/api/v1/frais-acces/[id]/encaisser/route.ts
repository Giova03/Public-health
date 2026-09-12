/**
 * POST /api/v1/frais-acces/[id]/encaisser — encaissement espèces (I5).
 *
 * Le ticket du CSPS est un encaissement CASH, pas une transaction FedaPay.
 * Machine forward-only : en_attente → paye est TERMINAL (re-décision 409).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import { findTicket, getState } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

interface EncaissementBody {
  montantXof?: number;
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "paiement:initier");
  if (garde.refus) return garde.refus;
  const { id } = await params;

  const corps = await request.json().catch(() => null) as EncaissementBody | null;
  const ticket = findTicket(id);
  if (!ticket) {
    return NextResponse.json(
      { title: "Encaissement impossible", detail: `Ticket introuvable : ${id}`, status: 404 },
      { status: 404 },
    );
  }
  if (ticket.statut !== "en_attente") {
    return NextResponse.json(
      {
        title: "Encaissement impossible",
        detail: `Ticket déjà réglé (${ticket.statut}) : en_attente → paye est forward-only`,
        status: 409,
      },
      { status: 409 },
    );
  }

  const montant = corps?.montantXof ?? ticket.montantXof;
  if (!Number.isInteger(montant) || montant < 0 || montant > 1_000_000) {
    return NextResponse.json(
      { title: "Encaissement impossible", detail: "Montant invalide (XOF, 0 à 1 000 000)", status: 400 },
      { status: 400 },
    );
  }

  ticket.statut = "paye";
  ticket.montantXof = montant;
  ticket.encaissePar = garde.token.sub;
  ticket.encaisseLe = new Date().toISOString();

  getState().auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "FRAIS_ACCES_ENCAISSE",
    entite: "frais_acces",
    entiteId: ticket.id,
    motif: `encaissement espèces ${montant} X CFA`,
    resultat: "SUCCESS",
  });
  return NextResponse.json(ticket);
}
