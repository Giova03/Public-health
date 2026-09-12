/**
 * GET /api/v1/statistics/snis?structureId=&mois=YYYY-MM — agrégats SNIS
 * (I11 : la donnée individuelle remonte ENFIN en statistiques nationales).
 * Permission : audit:lire (superviseur, admin).
 */

import { NextRequest, NextResponse } from "next/server";
import { getState } from "@/lib/demo/seed";
import { exigerPermission } from "@/lib/demo/guard";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const garde = exigerPermission(request, "audit:lire");
  if (garde.refus) return garde.refus;
  const params = request.nextUrl.searchParams;
  const structure = params.get("structureId") ?? "";
  const state = getState();

  const dansStructure = <T extends { facility?: string; structure?: string }>(x: T) =>
    !structure || x.facility === structure || x.structure === structure;

  const consultations = state.consultations.filter(dansStructure);
  const prescriptions = state.prescriptions.filter(dansStructure);
  const payments = state.payments.filter(dansStructure);
  const references = state.references.filter((r) => !structure || r.structureOrigine === structure);
  const appointments = state.appointments.filter((r) => !structure || r.structure === structure);
  const patientsConsultes = new Set(consultations.map((c) => c.patientId));
  const patients = state.patients.filter((p) => patientsConsultes.has(p.id));

  const age = (birthDate: string) => {
    const annees = (Date.now() - new Date(birthDate).getTime()) / (365.25 * 24 * 3600 * 1000);
    return annees;
  };

  const diagnosticsMap = new Map<string, number>();
  consultations.forEach((c) => {
    diagnosticsMap.set(c.diagnosticCode, (diagnosticsMap.get(c.diagnosticCode) ?? 0) + 1);
  });

  const nonAbouties = state.references.filter(
    (r) => r.statut === "envoyee"
      && Date.now() - new Date(r.createdAt).getTime() > 48 * 3600 * 1000
      && (!structure || r.structureOrigine === structure),
  ).length;

  return NextResponse.json(
    {
      structureId: structure,
      periode: new Date().toISOString().slice(0, 7),
      consultations: {
        total: consultations.length,
        moinsDe5: patients.filter((p) => age(p.birthDate) < 5).length,
        de5a14: patients.filter((p) => age(p.birthDate) >= 5 && age(p.birthDate) < 15).length,
        femmes15a49: patients.filter((p) => p.gender === "F" && age(p.birthDate) >= 15 && age(p.birthDate) < 50).length,
      },
      paludismeConfirme: consultations.filter((c) => c.examens.some((e) => e.positif)).length,
      diagnostics: Array.from(diagnosticsMap.entries())
        .map(([code, total]) => ({ code, total }))
        .sort((a, b) => b.total - a.total)
        .slice(0, 10),
      ordonnances: prescriptions.length,
      dispensations: prescriptions.reduce((t, r) => t + r.dispenses.length, 0),
      paiements: {
        inities: payments.length,
        encaisseXof: payments
          .filter((p) => p.state === "SUCCEEDED" || p.state === "RECONCILED")
          .reduce((t, p) => t + p.amountXof, 0),
        echecs: payments.filter((p) => p.state === "FAILED").length,
      },
      references: { envoyees: references.length, nonAbouties48h: nonAbouties },
      rendezVous: {
        demandes: appointments.length,
        honores: appointments.filter((r) => r.statut === "honore").length,
        annules: appointments.filter((r) => r.statut === "annule").length,
      },
      deces: state.patients.filter((p) => p.deceased).length,
      rupturesStock: state.stockItems.filter(
        (i) => i.quantity <= 0 && (!structure || i.structure === structure),
      ).length,
    },
    { headers: { "cache-control": "no-store" } },
  );
}
