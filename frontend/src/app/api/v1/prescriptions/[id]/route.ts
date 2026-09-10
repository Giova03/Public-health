/**
 * GET  /api/v1/prescriptions/[id] — détail complet (lignes + dispensations).
 * POST /api/v1/prescriptions/[id] — dispensation partielle ou contre-entrée.
 *
 * Règles E3 :
 *  - dispensation cumulée : le total dispensé (contre-entrées déduites)
 *    ne peut excéder la quantité prescrite ;
 *  - append-only : rien ne s'efface, une erreur se contre-entre ;
 *  - ordonnance soldée → COMPLETED automatique.
 */

import { NextRequest, NextResponse } from "next/server";
import type { DispenseLine } from "@/lib/types";
import { addDispense, applyDelta, findPrescription, newEntityId } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

/** Total dispensé net (contre-entrées déduites) par médicament. */
function netDispensed(prescriptionId: string): Map<string, number> {
  const p = findPrescription(prescriptionId);
  const net = new Map<string, number>();
  if (!p) return net;
  for (const d of p.dispenses) {
    const sign = d.counterEntryOf ? -1 : 1;
    for (const line of d.lines) {
      net.set(line.drug, (net.get(line.drug) ?? 0) + sign * line.quantity);
    }
  }
  return net;
}

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const prescription = findPrescription(id);
  if (!prescription) {
    return NextResponse.json(
      { title: "Ordonnance introuvable", status: 404 },
      { status: 404 },
    );
  }
  return NextResponse.json(
    { prescription, netDispensed: Array.from(netDispensed(id).entries()) },
    { headers: { "cache-control": "no-store" } },
  );
}

interface DispenseBody {
  action: "DISPENSE" | "COUNTER_ENTRY";
  pharmacist: string;
  source?: "INTERNE" | "PRIVE";
  lines: DispenseLine[];
  /** Contre-entrée : identifiant de la dispensation à inverser. */
  counterEntryOf?: string;
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const prescription = findPrescription(id);
  if (!prescription) {
    return NextResponse.json(
      { title: "Ordonnance introuvable", status: 404 },
      { status: 404 },
    );
  }
  if (prescription.status === "CANCELLED") {
    return NextResponse.json(
      { title: "Ordonnance annulée — dispensation impossible", status: 409 },
      { status: 409 },
    );
  }

  let body: DispenseBody;
  try {
    body = (await request.json()) as DispenseBody;
  } catch {
    return NextResponse.json(
      { title: "Corps de requête invalide", status: 400 },
      { status: 400 },
    );
  }
  if (!body.pharmacist || !Array.isArray(body.lines) || body.lines.length === 0) {
    return NextResponse.json(
      { title: "Pharmacien et lignes obligatoires", status: 422 },
      { status: 422 },
    );
  }

  if (body.action === "COUNTER_ENTRY") {
    if (!body.counterEntryOf) {
      return NextResponse.json(
        { title: "counterEntryOf requis pour une contre-entrée", status: 422 },
        { status: 422 },
      );
    }
    const target = prescription.dispenses.find((d) => d.id === body.counterEntryOf);
    if (!target) {
      return NextResponse.json(
        { title: "Dispensation d'origine introuvable", status: 422 },
        { status: 422 },
      );
    }
    if (target.counterEntryOf) {
      return NextResponse.json(
        { title: "Une contre-entrée ne se contre-entre pas", status: 409 },
        { status: 409 },
      );
    }
  }

  // Garde : cumul net + demande ≤ quantité prescrite (dispensation seule).
  if (body.action === "DISPENSE") {
    const net = netDispensed(id);
    for (const line of body.lines) {
      const prescribed = prescription.items.find((i) => i.drug === line.drug);
      if (!prescribed) {
        return NextResponse.json(
          {
            title: "Médicament absent de l'ordonnance",
            detail: line.drug,
            status: 422,
          },
          { status: 422 },
        );
      }
      const already = net.get(line.drug) ?? 0;
      if (already + line.quantity > prescribed.quantity) {
        return NextResponse.json(
          {
            title: "Dispensation supérieure au reste prescrit",
            detail: `${line.drug} : reste ${prescribed.quantity - already}, demandé ${line.quantity}`,
            status: 409,
          },
          { status: 409 },
        );
      }
    }
  }

  const event = {
    id: newEntityId(),
    at: new Date().toISOString(),
    pharmacist: body.pharmacist,
    source: body.source ?? "INTERNE",
    lines: body.lines,
    ...(body.action === "COUNTER_ENTRY" ? { counterEntryOf: body.counterEntryOf } : {}),
  };
  addDispense(id, event);

  // Soldée ? (cumul net couvre chaque ligne prescrite)
  const net = netDispensed(id);
  const solded = prescription.items.every(
    (i) => (net.get(i.drug) ?? 0) >= i.quantity,
  );
  if (solded && prescription.status === "ACTIVE") {
    prescription.status = "COMPLETED";
  }
  applyDelta("prescription.dispensed", prescription);

  return NextResponse.json(
    { prescription, event },
    { status: 201, headers: { "cache-control": "no-store" } },
  );
}
