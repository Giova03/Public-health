/**
 * GET  /api/v1/frais-acces — historique par patient OU file d'attente caisse.
 * POST /api/v1/frais-acces — ouverture du ticket du jour (I5, idempotente).
 *
 * Miroir honnête du backend V15 : un ticket par patient × structure × jour
 * (le rejeu renvoie le ticket existant, 200), écritures paiement:initier,
 * lectures paiement:lire. La consultation est refusée 402 tant que le
 * ticket du jour n'est pas réglé — le parcours monétaire réel du BF.
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import type { FraisAccesTicket } from "@/lib/types";
import { addTicket, getState, newEntityId, ticketDuJour } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "paiement:lire");
  if (garde.refus) return garde.refus;

  const params = request.nextUrl.searchParams;
  const patientId = params.get("patientId");
  const structureId = params.get("structureId");
  const statut = params.get("statut");

  // Historique par patient (périmètre explicite — contrat backend).
  if (patientId) {
    let tickets = getState().fraisAcces.filter((t) => t.patientId === patientId);
    if (structureId) tickets = tickets.filter((t) => t.structure === structureId);
    if (statut) tickets = tickets.filter((t) => t.statut === statut);
    return NextResponse.json(
      { tickets: tickets.slice(0, 100) },
      { headers: { "cache-control": "no-store" } },
    );
  }

  // File d'attente de la caisse : tickets en_attente du jour (FIFO).
  if (structureId && statut === "en_attente") {
    const aujourdhui = new Date().toISOString().slice(0, 10);
    const file = getState().fraisAcces
      .filter((t) => t.structure === structureId && t.statut === "en_attente"
        && t.createdAt.slice(0, 10) === aujourdhui)
      .slice(0, 100);
    return NextResponse.json(
      { tickets: file },
      { headers: { "cache-control": "no-store" } },
    );
  }

  return NextResponse.json(
    {
      title: "Recherche incomplète",
      detail: "patientId (historique) ou structureId + statut=en_attente (file caisse) requis",
      status: 400,
    },
    { status: 400 },
  );
}

interface OuvertureBody {
  patientId: string;
  structureId?: string;
  montantXof?: number;
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "paiement:initier");
  if (garde.refus) return garde.refus;

  const corps = await request.json().catch(() => null) as OuvertureBody | null;
  if (!corps?.patientId) {
    return NextResponse.json(
      { title: "Ticket impossible", detail: "Le patient est obligatoire", status: 400 },
      { status: 400 },
    );
  }
  const structure = corps.structureId ?? garde.token.structure ?? "CSPS Ouaga 12";
  const montant = corps.montantXof ?? 1000;
  if (!Number.isInteger(montant) || montant < 0 || montant > 1_000_000) {
    return NextResponse.json(
      { title: "Ticket impossible", detail: "Montant invalide (XOF, 0 à 1 000 000)", status: 400 },
      { status: 400 },
    );
  }

  const state = getState();
  const patient = state.patients.find((p) => p.id === corps.patientId && p.active);
  if (!patient) {
    return NextResponse.json(
      { title: "Ticket impossible", detail: `Patient introuvable : ${corps.patientId}`, status: 400 },
      { status: 400 },
    );
  }

  // Idempotence du jour : rejeu → MÊME ticket, 200.
  const existant = ticketDuJour(corps.patientId, structure);
  if (existant) {
    return NextResponse.json({ ...existant, replayed: true });
  }

  const ticket: FraisAccesTicket = {
    id: `t-${newEntityId().slice(0, 8)}`,
    patientId: corps.patientId,
    patientName: { family: patient.name.family, given: patient.name.given },
    structure,
    statut: "en_attente",
    montantXof: montant,
    ouvertPar: garde.token.sub,
    createdAt: new Date().toISOString(),
  };
  addTicket(ticket);
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: "FRAIS_ACCES_CREATED",
    entite: "frais_acces",
    entiteId: ticket.id,
    motif: `ticket modérateur ${montant} X CFA`,
    resultat: "SUCCESS",
  });
  return NextResponse.json(ticket, { status: 201 });
}
