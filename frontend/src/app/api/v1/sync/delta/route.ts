/**
 * GET /api/v1/sync/delta?cursor= — tirer les changements serveur (E2).
 * Répond { cursor, entries } ; curseur 0 = première synchronisation.
 */

import { NextRequest, NextResponse } from "next/server";
import { getDelta } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const cursorParam = request.nextUrl.searchParams.get("cursor") ?? "0";
  const cursor = Number.parseInt(cursorParam, 10);
  const safeCursor = Number.isNaN(cursor) || cursor < 0 ? 0 : cursor;

  const { cursor: nextCursor, entries } = getDelta(safeCursor);

  return NextResponse.json(
    { cursor: nextCursor, entries },
    { headers: { "cache-control": "no-store" } },
  );
}
