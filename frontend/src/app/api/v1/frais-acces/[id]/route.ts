/**
 * GET /api/v1/frais-acces/[id] — détail du ticket d'accès.
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import { findTicket } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "paiement:lire");
  if (garde.refus) return garde.refus;
  const { id } = await params;
  const ticket = findTicket(id);
  if (!ticket) {
    return NextResponse.json(
      { title: "Ticket introuvable", detail: `Ticket introuvable : ${id}`, status: 404 },
      { status: 404 },
    );
  }
  return NextResponse.json(ticket, { headers: { "cache-control": "no-store" } });
}
