"use client";

/**
 * Vue Statistiques SNIS (V14, I11) — la donnée remonte.
 * Agrégats mensuels par structure : consultations par tranche d'âge,
 * paludisme CONFIRMÉ (TDR+), diagnostics codés, paiements, références,
 * décès, ruptures. C'est la motivation d'un système NATIONAL.
 */

import { useEffect } from "react";
import { FileDown } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";

const fmtXof = new Intl.NumberFormat("fr-FR");

export function StatisticsView() {
  const snis = useAppStore((s) => s.snis);
  const loadSnis = useAppStore((s) => s.loadSnis);

  useEffect(() => { void loadSnis(); }, [loadSnis]);

  if (!snis) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold tracking-tight">Statistiques SNIS</h1>
        <p className="text-sm text-muted-foreground">
          Chargement des agrégats mensuels…
        </p>
      </div>
    );
  }

  const maxDiag = Math.max(1, ...snis.diagnostics.map((d) => d.total));

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Statistiques SNIS</h1>
          <p className="text-sm text-muted-foreground">
            Rapport mensuel · période {snis.periode} — agrégats calculés sur les
            données individuelles (l&apos;export CSV alimente DHIS2).
          </p>
        </div>
        <a
          href="/api/v1/statistics/snis"
          className="inline-flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium hover:border-primary/40"
        >
          <FileDown className="h-4 w-4" /> Export
        </a>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Consultations</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold">{snis.consultations.total}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              &lt;5 ans : {snis.consultations.moinsDe5} · 5-14 : {snis.consultations.de5a14} ·
              femmes 15-49 : {snis.consultations.femmes15a49}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Paludisme confirmé</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold text-emerald-600">{snis.paludismeConfirme}</p>
            <p className="mt-1 text-xs text-muted-foreground">TDR / goutte épaisse POSITIFS (la preuve, pas l&apos;opinion)</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Encaissé (XOF)</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold">{fmtXof.format(snis.paiements.encaisseXof)}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {snis.paiements.inities} initié(s) · {snis.paiements.echecs} échec(s)
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm">Références</CardTitle></CardHeader>
          <CardContent>
            <p className="text-3xl font-bold">{snis.references.envoyees}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              dont <strong className="text-red-600">{snis.references.nonAbouties48h} non aboutie(s) &gt;48h</strong>
            </p>
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader><CardTitle className="text-base">Diagnostics codés (top)</CardTitle></CardHeader>
          <CardContent className="space-y-2.5">
            {snis.diagnostics.map((d) => (
              <div key={d.code}>
                <div className="flex items-center justify-between text-xs">
                  <span className="font-mono">{d.code}</span>
                  <span className="tnum font-semibold">{d.total}</span>
                </div>
                <Progress value={(d.total / maxDiag) * 100} className="mt-1 h-1.5" />
              </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="text-base">Activité & signaux</CardTitle></CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div className="flex justify-between"><span className="text-muted-foreground">Ordonnances émises</span><span className="tnum font-semibold">{snis.ordonnances}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Dispensations</span><span className="tnum font-semibold">{snis.dispensations}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">RDV honorés / annulés</span><span className="tnum font-semibold">{snis.rendezVous.honores} / {snis.rendezVous.annules}</span></div>
            <div className="flex justify-between"><span className="text-muted-foreground">Décès déclarés</span><span className="tnum font-semibold">{snis.deces}</span></div>
            <div className="flex justify-between items-center">
              <span className="text-muted-foreground">Ruptures de stock</span>
              <Badge variant="secondary" className={snis.rupturesStock > 0 ? "bg-red-500/10 text-red-700 dark:text-red-400" : ""}>
                {snis.rupturesStock}
              </Badge>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
