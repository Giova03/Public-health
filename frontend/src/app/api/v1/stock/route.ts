/**
 * GET  /api/v1/stock?structureId= — état du stock, ruptures, mouvements (I8).
 * POST /api/v1/stock/mouvements — réception CAMEG/CSD ou inventaire COCOM.
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";
import type { StockItem } from "@/lib/types";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "stock:gerer");
  if (garde.refus) return garde.refus;
  const structureId = request.nextUrl.searchParams.get("structureId") ?? garde.token.structure ?? "";
  const state = getState();
  const items = state.stockItems.filter((i) => !structureId || i.structure === structureId);
  const mouvements = state.stockMouvements
    .filter((m) => !structureId || state.stockItems.some((i) => i.structure === structureId && i.medicationCode === m.medicationCode))
    .slice(0, 50);
  return NextResponse.json(
    {
      structureId,
      items,
      mouvements,
      ruptures: items.filter((i) => i.quantity <= 0).length,
      sousSeuil: items.filter((i) => i.quantity > 0 && i.quantity <= i.seuilAlerte).length,
    },
    { headers: { "cache-control": "no-store" } },
  );
}

export async function POST(request: NextRequest) {
  const garde = exigerPermission(request, "stock:gerer");
  if (garde.refus) return garde.refus;
  const corps = await request.json().catch(() => null) as {
    structureId?: string;
    medicationCode?: string;
    medicationLabel?: string;
    type?: "reception" | "ajustement";
    quantity?: number;
    motif?: string;
  } | null;
  if (!corps?.medicationCode || !corps.type || corps.quantity === undefined || corps.quantity === null) {
    return NextResponse.json(
      { title: "Mouvement refusé", detail: "Code médicament, type et quantité obligatoires", status: 400 },
      { status: 400 },
    );
  }
  if (corps.quantity < 0) {
    return NextResponse.json(
      { title: "Mouvement refusé", detail: "Un solde d'inventaire ne peut pas être négatif", status: 400 },
      { status: 400 },
    );
  }
  const state = getState();
  const structure = corps.structureId ?? garde.token.structure ?? "CSPS Ouaga 12";
  let item = state.stockItems.find(
    (i) => i.structure === structure && i.medicationCode === corps.medicationCode,
  );
  if (!item) {
    item = {
      id: `s-${Date.now().toString(36)}`,
      structure,
      medicationCode: corps.medicationCode,
      medicationLabel: corps.medicationLabel ?? corps.medicationCode,
      quantity: 0,
      seuilAlerte: 10,
    } satisfies StockItem;
    state.stockItems.push(item);
  }
  const ecart = corps.type === "reception"
    ? corps.quantity
    : corps.quantity - item.quantity;
  item.quantity = corps.type === "reception" ? item.quantity + corps.quantity : corps.quantity;
  state.stockMouvements.unshift({
    id: `m-${Date.now().toString(36)}`,
    medicationCode: item.medicationCode,
    type: corps.type,
    quantity: Math.abs(ecart) || 1,
    motif: corps.motif ?? (corps.type === "reception" ? "réception" : "inventaire"),
    date: new Date().toISOString(),
  });
  state.auditLog.unshift({
    date: new Date().toISOString(),
    acteur: garde.token.sub,
    action: corps.type === "reception" ? "STOCK_RECEPTION" : "STOCK_INVENTAIRE",
    entite: "stock",
    entiteId: item.id,
    motif: `${item.medicationCode} ${ecart >= 0 ? "+" : ""}${ecart}`,
    resultat: "SUCCESS",
  });
  if (item.quantity <= item.seuilAlerte) {
    state.auditLog.unshift({
      date: new Date().toISOString(),
      acteur: garde.token.sub,
      action: "STOCK_ALERTE_SEUIL",
      entite: "stock",
      entiteId: item.id,
      motif: `${item.medicationLabel} sous le seuil (${item.quantity})`,
      resultat: "SUCCESS",
    });
  }
  return NextResponse.json(item);
}
