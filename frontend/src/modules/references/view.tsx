"use client";

/**
 * Vue Références (V14, I7) — la pyramide sanitaire tracée.
 * Envoyée → reçue → hospitalisée → retournée (contre-référence
 * OBLIGATOIRE pour refermer la boucle). Les non-abouties > 48 h
 * sont l'indicateur de performance du système de santé.
 */

import { useEffect } from "react";
import { ArrowRightLeft, Siren } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const STATUTS: Record<string, { label: string; classe: string }> = {
  envoyee: { label: "Envoyée", classe: "bg-amber-500/10 text-amber-700 dark:text-amber-400" },
  recue: { label: "Reçue", classe: "bg-sky-500/10 text-sky-700 dark:text-sky-400" },
  hospitalisee: { label: "Hospitalisée", classe: "bg-indigo-500/10 text-indigo-700 dark:text-indigo-400" },
  retournee: { label: "Retournée ✓", classe: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400" },
  cloturee: { label: "Clôturée", classe: "bg-zinc-500/10 text-zinc-700 dark:text-zinc-400" },
};

export function ReferencesView() {
  const references = useAppStore((s) => s.references);
  const loadReferences = useAppStore((s) => s.loadReferences);
  const transitionReference = useAppStore((s) => s.transitionReference);
  const peutGerer = usePermission("reference:gerer");
  const { toast } = useToast();

  useEffect(() => { void loadReferences(); }, [loadReferences]);

  const nonAbouties = references.filter(
    (r) => r.statut === "envoyee" && Date.now() - new Date(r.createdAt).getTime() > 48 * 3600 * 1000,
  );

  async function agir(id: string, action: "reception" | "hospitalisation" | "contre-reference") {
    let resume: string | undefined;
    if (action === "contre-reference") {
      resume = window.prompt("Résumé de contre-référence (OBLIGATOIRE — la boucle se referme) :") ?? "";
      if (!resume.trim()) return;
    }
    const resultat = await transitionReference(id, action, resume);
    if (resultat.status !== "ok") {
      toast({ title: "Transition refusée", description: "message" in resultat ? resultat.message : "Transition refusée", variant: "destructive" });
      return;
    }
    toast({ title: "Référence mise à jour" });
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Références & contre-références</h1>
        <p className="text-sm text-muted-foreground">
          La fiche numérique remplace les 3 volets papier : la continuité des
          soins CSPS → CMA / CHR / CHU est tracée de bout en bout.
        </p>
      </div>

      {nonAbouties.length > 0 && (
        <Card className="border-red-500/40 bg-red-500/5">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm text-red-700 dark:text-red-400">
              <Siren className="h-4 w-4" /> {nonAbouties.length} référence(s) NON ABOUTIE(S) — envoyée(s) depuis plus de 48 h sans réception
            </CardTitle>
          </CardHeader>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Fil de références ({references.length})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {references.length === 0 && (
            <p className="text-sm text-muted-foreground">
              Aucune référence — initiez-en une depuis la fiche d&apos;un patient.
            </p>
          )}
          {references.map((fiche) => {
            const ancienne = fiche.statut === "envoyee"
              && Date.now() - new Date(fiche.createdAt).getTime() > 48 * 3600 * 1000;
            return (
              <div key={fiche.id} className={`rounded-2xl border p-4 ${ancienne ? "border-red-500/40" : ""}`}>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary" className={STATUTS[fiche.statut]?.classe}>
                    {STATUTS[fiche.statut]?.label ?? fiche.statut}
                  </Badge>
                  <span className="text-sm font-medium">
                    {fiche.patientName ? `${fiche.patientName.family} ${fiche.patientName.given}` : "Patient"}
                  </span>
                  {fiche.urgence && (
                    <Badge variant="secondary" className="bg-red-500/10 text-red-700 dark:text-red-400">URGENCE</Badge>
                  )}
                  <span className="text-xs text-muted-foreground">
                    {fiche.structureOrigine} → {fiche.structureDestination} ·{" "}
                    {new Date(fiche.createdAt).toLocaleDateString("fr-FR")}
                  </span>
                </div>
                <p className="mt-1.5 text-sm">{fiche.motif}</p>
                {fiche.contreReference && (
                  <div className="mt-2 rounded-xl bg-emerald-500/10 p-3 text-xs leading-relaxed text-emerald-800 dark:text-emerald-300">
                    <strong>Contre-référence :</strong> {fiche.contreReference}
                  </div>
                )}
                {peutGerer && fiche.statut !== "retournee" && fiche.statut !== "cloturee" && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {fiche.statut === "envoyee" && (
                      <Button size="sm" variant="outline" className="rounded-full"
                        onClick={() => agir(fiche.id, "reception")}>
                        <ArrowRightLeft className="h-4 w-4" /> Marquer reçue
                      </Button>
                    )}
                    {fiche.statut === "recue" && (
                      <Button size="sm" variant="outline" className="rounded-full"
                        onClick={() => agir(fiche.id, "hospitalisation")}>
                        Hospitalisation
                      </Button>
                    )}
                    {(fiche.statut === "recue" || fiche.statut === "hospitalisee") && (
                      <Button size="sm" variant="medical" className="rounded-full"
                        onClick={() => agir(fiche.id, "contre-reference")}>
                        Contre-référence…
                      </Button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}
