/**
 * GET  /api/v1/payments/[id] — détail.
 * POST /api/v1/payments/[id] — webhook / progression d'état (E4).
 *
 * La machine à 8 états est forward-only : une transition interdite
 * renvoie 409 (comme le backend sur les rétrogradations).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import type { PaymentRecord } from "@/lib/types";
import { PAYMENT_TRANSITIONS } from "@/lib/types";
import { applyDelta, findPayment, transitionPayment } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const gardeLecture = exigerPermission(request, "paiement:lire");
  if (gardeLecture.refus) return gardeLecture.refus;
  const { id } = await params;
  const payment = findPayment(id);
  if (!payment) {
    return NextResponse.json(
      { title: "Paiement introuvable", status: 404 },
      { status: 404 },
    );
  }
  return NextResponse.json(
    { payment, allowedTransitions: PAYMENT_TRANSITIONS[payment.state] },
    { headers: { "cache-control": "no-store" } },
  );
}

interface ProgressBody {
  target: PaymentRecord["state"];
  /** WEBHOOK = notification opérateur ; RECONCILIATION = job nocturne. */
  event?: "WEBHOOK" | "RECONCILIATION";
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const garde = exigerPermission(request, "paiement:lire");
  if (garde.refus) return garde.refus;
  const { id } = await params;
  const payment = findPayment(id);
  if (!payment) {
    return NextResponse.json(
      { title: "Paiement introuvable", status: 404 },
      { status: 404 },
    );
  }

  let body: ProgressBody;
  try {
    body = (await request.json()) as ProgressBody;
  } catch {
    return NextResponse.json(
      { title: "Corps de requête invalide", status: 400 },
      { status: 400 },
    );
  }

  const allowed = PAYMENT_TRANSITIONS[payment.state];
  if (!allowed.includes(body.target)) {
    return NextResponse.json(
      {
        title: "Transition interdite",
        detail: `${payment.state} → ${body.target} : la machine à états est forward-only.`,
        status: 409,
        currentState: payment.state,
        allowedTransitions: allowed,
      },
      { status: 409 },
    );
  }

  transitionPayment(id, body.target);
  applyDelta("payment.state_changed", payment);

  return NextResponse.json(
    { payment, event: body.event ?? "WEBHOOK" },
    { headers: { "cache-control": "no-store" } },
  );
}
