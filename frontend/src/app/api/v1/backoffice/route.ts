/**
 * GET /api/v1/backoffice — gouvernance de base (E6) :
 * utilisateurs, structures, indicateurs de supervision.
 */

import { NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";

export const dynamic = "force-dynamic";

export async function GET() {
  const s = getState();
  const activePatients = s.patients.filter((p) => p.active).length;
  const activePrescriptions = s.prescriptions.filter((r) => r.status === "ACTIVE").length;
  const pendingPayments = s.payments.filter(
    (m) => m.state === "PENDING" || m.state === "INITIATED",
  ).length;
  const failedPayments = s.payments.filter((m) => m.state === "FAILED").length;

  return NextResponse.json(
    {
      users: s.users,
      facilities: s.facilities,
      stats: {
        activePatients,
        activePrescriptions,
        pendingPayments,
        failedPayments,
        totalPayments: s.payments.length,
      },
      serverBoot: s.bootedAt,
    },
    { headers: { "cache-control": "no-store" } },
  );
}
