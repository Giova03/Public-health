/**
 * POST /api/v1/payments/reconcile — réconciliation nocturne simulée (E4).
 * Les SUCCEEDED passent RECONCILED ; les échecs orphelins restent visibles
 * pour revue. Renvoie le nombre de paiements réconciliés.
 */

import { NextResponse } from "next/server";
import { getState, runNightlyReconciliation } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function POST() {
  const count = runNightlyReconciliation();
  const orphans = getState().payments.filter(
    (m) =>
      m.state === "FAILED" ||
      (m.state === "PENDING" &&
        Date.now() - Date.parse(m.updatedAt) > 30 * 60_000),
  ).length;

  return NextResponse.json(
    {
      reconciled: count,
      orphansForReview: orphans,
      ranAt: new Date().toISOString(),
    },
    { headers: { "cache-control": "no-store" } },
  );
}
