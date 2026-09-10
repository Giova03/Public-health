/**
 * POST /api/v1/sync — drain du lot outbox, idempotent par opId (E2).
 *
 * Chaque opération est appliquée au plus une fois : un rejeu (réseau
 * coupé après envoi, accusé perdu) renvoie « duplicate » avec l'entité
 * déjà produite. Le client vide l'outbox uniquement sur accusé.
 */

import { NextRequest, NextResponse } from "next/server";
import type {
  CreatePatientInput,
  DispenseLine,
  OperationAck,
  Prescription,
  PrescriptionItem,
  SyncOperation,
} from "@/lib/types";
import {
  addPatient,
  addDispense,
  addPrescription,
  applyDelta,
  findPayment,
  findPrescription,
  getState,
  newEntityId,
  nextPhReference,
  transitionPayment,
  ackOperation,
} from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

interface UplinkBody {
  deviceId: string;
  operations: SyncOperation[];
}

function patientNameOf(patientId: string) {
  return getState().patients.find((p) => p.id === patientId)?.name;
}

function applyOperation(op: SyncOperation): OperationAck {
  switch (op.kind) {
    case "patient.create": {
      const input = op.payload as CreatePatientInput;
      const existing = getState().patients.find(
        (p) => p.clientRequestId === input.clientRequestId,
      );
      if (existing) {
        return { opId: op.opId, result: "duplicate", entity: existing };
      }
      const now = new Date().toISOString();
      const patient = {
        id: op.entityId,
        phReference: nextPhReference(),
        clientRequestId: input.clientRequestId,
        name: input.name,
        gender: input.gender,
        birthDate: input.birthDate,
        phone: input.phone,
        identifiers: input.identifiers ?? [],
        facility: input.facility,
        village: input.village,
        active: true,
        version: 1,
        createdAt: now,
        updatedAt: now,
      };
      addPatient(patient);
      applyDelta("patient.created", patient);
      return { opId: op.opId, result: "applied", entity: patient };
    }

    case "prescription.create": {
      const payload = op.payload as {
        patientId: string;
        prescriber: string;
        facility: string;
        diagnosis: string;
        items: PrescriptionItem[];
      };
      const existing = findPrescription(op.entityId);
      if (existing) {
        return { opId: op.opId, result: "duplicate", entity: existing };
      }
      const now = new Date().toISOString();
      const prescription: Prescription = {
        id: op.entityId,
        patientId: payload.patientId,
        patientName: patientNameOf(payload.patientId),
        prescriber: payload.prescriber,
        facility: payload.facility,
        date: now,
        diagnosis: payload.diagnosis,
        items: payload.items,
        dispenses: [],
        status: "ACTIVE",
        createdAt: now,
      };
      addPrescription(prescription);
      applyDelta("prescription.created", prescription);
      return { opId: op.opId, result: "applied", entity: prescription };
    }

    case "prescription.dispense":
    case "prescription.counterEntry": {
      const payload = op.payload as {
        prescriptionId: string;
        pharmacist: string;
        source: "INTERNE" | "PRIVE";
        lines: DispenseLine[];
        counterEntryOf?: string;
      };
      const prescription = findPrescription(payload.prescriptionId);
      if (!prescription) {
        return {
          opId: op.opId,
          result: "rejected",
          error: `Ordonnance ${payload.prescriptionId} introuvable`,
        };
      }
      const already = prescription.dispenses.find((d) => d.id === op.entityId);
      if (already) {
        return { opId: op.opId, result: "duplicate", entity: prescription };
      }
      const event = {
        id: op.entityId,
        at: new Date().toISOString(),
        pharmacist: payload.pharmacist,
        source: payload.source,
        lines: payload.lines,
        ...(payload.counterEntryOf ? { counterEntryOf: payload.counterEntryOf } : {}),
      };
      addDispense(payload.prescriptionId, event);
      applyDelta("prescription.dispensed", prescription);
      return { opId: op.opId, result: "applied", entity: prescription };
    }

    case "payment.initiate": {
      const payload = op.payload as {
        clientRequestId: string;
        patientId: string;
        purpose: string;
        amountXof: number;
        channel: "MOBILE_MONEY" | "ESPECES" | "CARTE";
        prescriptionId?: string;
        facility: string;
      };
      const existing = findPayment(op.entityId);
      if (existing) {
        return { opId: op.opId, result: "duplicate", entity: existing };
      }
      const now = new Date().toISOString();
      const payment = {
        id: op.entityId,
        clientRequestId: payload.clientRequestId,
        patientId: payload.patientId,
        patientName: patientNameOf(payload.patientId),
        ...(payload.prescriptionId ? { prescriptionId: payload.prescriptionId } : {}),
        purpose: payload.purpose,
        amountXof: payload.amountXof,
        channel: payload.channel,
        state: "INITIATED" as const,
        facility: payload.facility,
        createdAt: now,
        updatedAt: now,
      };
      getState().payments.push(payment);
      applyDelta("payment.created", payment);
      return { opId: op.opId, result: "applied", entity: payment };
    }

    default:
      return {
        opId: op.opId,
        result: "rejected",
        error: `Type d'opération inconnu : ${op.kind}`,
      };
  }
}

export async function POST(request: NextRequest) {
  let body: UplinkBody;
  try {
    body = (await request.json()) as UplinkBody;
  } catch {
    return NextResponse.json(
      { title: "Corps de requête invalide", status: 400 },
      { status: 400 },
    );
  }

  if (!body.deviceId || !Array.isArray(body.operations)) {
    return NextResponse.json(
      { title: "deviceId et operations requis", status: 422 },
      { status: 422 },
    );
  }

  const acks: OperationAck[] = body.operations.map((op) =>
    ackOperation(op.opId, () => applyOperation(op)),
  );

  // Effet de bord utile à la démo : les paiements INITIATED du lot passent
  // PENDING dès que le serveur les voit (réseau opérateur joint).
  for (const ack of acks) {
    const entity = ack.entity;
    if (entity && "state" in entity && entity.state === "INITIATED") {
      transitionPayment(entity.id, "PENDING");
      applyDelta("payment.pending", entity);
    }
  }

  return NextResponse.json(
    { deviceId: body.deviceId, acks, receivedAt: new Date().toISOString() },
    { headers: { "cache-control": "no-store" } },
  );
}
