/**
 * GET  /api/v1/patients — recherche miroir publique (E1).
 * POST /api/v1/patients — création idempotente ; 409 = contrat UX doublons.
 */

import { NextRequest, NextResponse } from "next/server";
import { exigerPermission } from "@/lib/demo/guard";
import type { ConflictPatientBody, CreatePatientInput, Patient } from "@/lib/types";
import { findDuplicates, normalize } from "@/lib/demo/matcher";
import {
  addPatient,
  applyDelta,
  findPatient,
  getState,
  newEntityId,
  nextPhReference,
} from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  // V14 (I1/I2) : l'API n'est plus un registre ouvert — jeton + permission.
  const garde = exigerPermission(request, "patient:lire");
  if (garde.refus) return garde.refus;
  const params = request.nextUrl.searchParams;
  const q = normalize(params.get("q") ?? "");
  const family = normalize(params.get("family") ?? "");
  const phone = params.get("phone") ?? "";

  const patients = getState().patients.filter((p) => p.active);

  const filtered = patients.filter((p) => {
    if (family && !normalize(p.name.family).startsWith(family)) return false;
    if (phone && !(p.phone ?? "").includes(phone.replace(/\D/g, ""))) return false;
    if (q) {
      const hay = normalize(
        `${p.name.family} ${p.name.given} ${p.phone ?? ""} ${p.phReference}`,
      );
      if (!hay.includes(q)) return false;
    }
    return true;
  });

  return NextResponse.json(
    { patients: filtered.slice(0, 50) },
    { headers: { "cache-control": "no-store" } },
  );
}

interface CreateBody extends CreatePatientInput {
  /** Création forcée après examen du 409 — motif obligatoire. */
  forceCreate?: boolean;
  reason?: string;
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "patient:ecrire");
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

  if (!body.clientRequestId || !body.name?.family || !body.name?.given || !body.birthDate || !body.facility) {
    return NextResponse.json(
      { title: "Champs obligatoires manquants", status: 422 },
      { status: 422 },
    );
  }

  // Idempotence offline : rejeu du même clientRequestId → même dossier, 200.
  const replay = getState().patients.find(
    (p) => p.clientRequestId === body.clientRequestId,
  );
  if (replay) {
    return NextResponse.json({ patient: replay, replayed: true });
  }

  // Détection de doublons AVANT insertion (E1) — 409 = contrat UX.
  if (!body.forceCreate) {
    const candidates = findDuplicates(
      {
        family: body.name.family,
        given: body.name.given,
        birthDate: body.birthDate,
        phone: body.phone,
        nunp: body.identifiers?.find((i) => i.type === "NUNP")?.value,
      },
      getState().patients,
    );

    if (candidates.length > 0) {
      const conflict: ConflictPatientBody = {
        title: "Dossiers similaires détectés",
        detail:
          "Des dossiers proches existent déjà. Examinez les candidats : choisir un dossier existant évite un doublon dans le MPI national.",
        status: 409,
        candidates: candidates.slice(0, 5),
      };
      return NextResponse.json(conflict, {
        status: 409,
        headers: { "cache-control": "no-store" },
      });
    }
  }

  if (body.forceCreate && !body.reason) {
    return NextResponse.json(
      {
        title: "Motif obligatoire",
        detail: "La création forcée après doublons exige un motif tracé (audit).",
        status: 422,
      },
      { status: 422 },
    );
  }

  const now = new Date().toISOString();
  const patient: Patient = {
    id: newEntityId(),
    phReference: nextPhReference(),
    clientRequestId: body.clientRequestId,
    ...(body.forceCreate ? { forcedReason: body.reason } : {}),
    name: body.name,
    gender: body.gender,
    birthDate: body.birthDate,
    phone: body.phone,
    identifiers: body.identifiers ?? [],
    facility: body.facility,
    village: body.village,
    active: true,
    version: 1,
    createdAt: now,
    updatedAt: now,
  };
  addPatient(patient);
  applyDelta("patient.created", patient);

  return NextResponse.json({ patient }, { status: 201 });
}
