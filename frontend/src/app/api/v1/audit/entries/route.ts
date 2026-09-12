/**
 * GET /api/v1/audit/entries?limit= — journal d'audit (I16 : la chaîne
 * existait, l'écran manquait). Permission : audit:lire.
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "audit:lire");
  if (garde.refus) return garde.refus;
  const action = request.nextUrl.searchParams.get("action") ?? undefined;
  const limit = Math.min(Math.max(Number(request.nextUrl.searchParams.get("limit") ?? 100), 1), 500);
  const entries = getState().auditLog
    .filter((e) => !action || e.action === action)
    .slice(0, limit);
  return NextResponse.json({ entries }, { headers: { "cache-control": "no-store" } });
}
