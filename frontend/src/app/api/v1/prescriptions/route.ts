/**
 * GET  /api/v1/prescriptions — liste (filtre patientId / statut).
 * POST /api/v1/prescriptions — rédaction d'ordonnance (E3, append-only).
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import type { Prescription, PrescriptionItem } from "@/lib/types";
import { addPrescription, applyDelta, findPatient, getState, newEntityId } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "prescription:lire");
  if (garde.refus) return garde.refus;
  const params = request.nextUrl.searchParams;
  const patientId = params.get("patientId");
  const status = params.get("status");

  let prescriptions = getState().prescriptions;
  if (patientId) prescriptions = prescriptions.filter((r) => r.patientId === patientId);
  if (status) prescriptions = prescriptions.filter((r) => r.status === status);

  // Les plus récentes d'abord (append-only : createdAt fait foi).
  const sorted = [...prescriptions].sort(
    (a, b) => b.createdAt.localeCompare(a.createdAt),
  );

  return NextResponse.json(
    { prescriptions: sorted },
    { headers: { "cache-control": "no-store" } },
  );
}

interface CreateBody {
  patientId: string;
  prescriber: string;
  facility: string;
  diagnosis: string;
  items: PrescriptionItem[];
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "prescription:ecrire");
  if (garde.refus) return garde.refus;
  let body: CreateBody;
  try {
    body = (await request.json()) as CreateBody;
  } catch {
    return NextResponse.json(
      { title: "Corps de requête invalide", status: 400 },
      { status: 400 },
    );
  }

  const patient = findPatient(body.patientId);
  if (!patient || !patient.active) {
    return NextResponse.json(
      { title: "Patient introuvable ou fusionné", status: 422 },
      { status: 422 },
    );
  }
  if (!body.diagnosis || !Array.isArray(body.items) || body.items.length === 0) {
    return NextResponse.json(
      { title: "Diagnostic et lignes obligatoires", status: 422 },
      { status: 422 },
    );
  }
  if (body.items.some((i) => !i.drug || i.quantity <= 0)) {
    return NextResponse.json(
      { title: "Ligne de prescription invalide (médicament, quantité > 0)", status: 422 },
      { status: 422 },
    );
  }

  const now = new Date().toISOString();
  const prescription: Prescription = {
    id: newEntityId(),
    patientId: body.patientId,
    patientName: patient.name,
    prescriber: body.prescriber,
    facility: body.facility,
    date: now,
    diagnosis: body.diagnosis,
    items: body.items,
    dispenses: [],
    status: "ACTIVE",
    createdAt: now,
  };
  addPrescription(prescription);
  applyDelta("prescription.created", prescription);

  return NextResponse.json({ prescription }, { status: 201 });
}
