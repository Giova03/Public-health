/**
 * GET  /api/v1/payments — registre des paiements (filtres état/patient).
 * POST /api/v1/payments — initiation idempotente par clientRequestId (E4).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import type { PaymentRecord } from "@/lib/types";
import { applyDelta, findPatient, getState, newEntityId } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "paiement:lire");
  if (garde.refus) return garde.refus;
  const params = request.nextUrl.searchParams;
  const patientId = params.get("patientId");
  const state = params.get("state");

  let payments = getState().payments;
  if (patientId) payments = payments.filter((m) => m.patientId === patientId);
  if (state) payments = payments.filter((m) => m.state === state);

  const sorted = [...payments].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  );

  return NextResponse.json(
    { payments: sorted },
    { headers: { "cache-control": "no-store" } },
  );
}

interface InitiateBody {
  clientRequestId: string;
  patientId: string;
  prescriptionId?: string;
  purpose: string;
  amountXof: number;
  channel: "MOBILE_MONEY" | "ESPECES" | "CARTE";
  facility: string;
}

export async function POST(request: NextRequest) {
  // V14 (I2) : initiation — agent financier, infirmier ICP (caisse CSPS), admin.
  const garde = exigerPermission(request, "paiement:initier");
  if (garde.refus) return garde.refus;
  let body: InitiateBody;
  try {
    body = (await request.json()) as InitiateBody;
  } catch {
    return NextResponse.json(
      { title: "Corps de requête invalide", status: 400 },
      { status: 400 },
    );
  }

  if (!body.clientRequestId || !body.patientId || !body.purpose || !body.amountXof || !body.channel) {
    return NextResponse.json(
      { title: "Champs obligatoires manquants", status: 422 },
      { status: 422 },
    );
  }
  if (!Number.isInteger(body.amountXof) || body.amountXof <= 0) {
    return NextResponse.json(
      {
        title: "Montant invalide",
        detail: "XOF sans centimes, entier strictement positif (@Digits 12/0).",
        status: 422,
      },
      { status: 422 },
    );
  }

  // Idempotence : rejeu → 200 avec le paiement existant.
  const replay = getState().payments.find(
    (m) => m.clientRequestId === body.clientRequestId,
  );
  if (replay) {
    return NextResponse.json({ payment: replay, replayed: true });
  }

  const patient = findPatient(body.patientId);
  if (!patient || !patient.active) {
    return NextResponse.json(
      { title: "Patient introuvable ou fusionné", status: 422 },
      { status: 422 },
    );
  }

  const now = new Date().toISOString();
  const payment: PaymentRecord = {
    id: newEntityId(),
    clientRequestId: body.clientRequestId,
    patientId: body.patientId,
    patientName: patient.name,
    ...(body.prescriptionId ? { prescriptionId: body.prescriptionId } : {}),
    purpose: body.purpose,
    amountXof: body.amountXof,
    channel: body.channel,
    state: "INITIATED",
    facility: body.facility,
    createdAt: now,
    updatedAt: now,
  };
  getState().payments.push(payment);
  applyDelta("payment.created", payment);

  return NextResponse.json({ payment }, { status: 201 });
}
