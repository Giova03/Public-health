"use client";

/**
 * Historique clinique V14 (I4/I15/I7) — les CONSULTATIONS persistées
 * (motif, constantes, diagnostic codé, notes), la déclaration de décès
 * et l'initiation de référence, directement dans la fiche patient.
 */

import { useEffect } from "react";
import { Activity, FileWarning, FlaskConical, Stethoscope } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { TYPES_EXAMENS, type Patient } from "@/lib/types";

export function ConsultationHistory({ patient }: { patient: Patient }) {
  const consultations = useAppStore((s) => s.consultations);
  const loadConsultations = useAppStore((s) => s.loadConsultations);
  const declareDeath = useAppStore((s) => s.declareDeath);
  const createReference = useAppStore((s) => s.createReference);
  const enregistrerResultatExamen = useAppStore((s) => s.enregistrerResultatExamen);
  const goTo = useAppStore((s) => s.goTo);
  const peutEcrire = usePermission("consultation:ecrire");
  const peutReferer = usePermission("reference:gerer");
  const peutLabo = usePermission("laboratoire:ecrire");
  const { toast } = useToast();

  useEffect(() => {
    void loadConsultations(patient.id);
  }, [loadConsultations, patient.id]);

  const duPatient = consultations.filter((c) => c.patientId === patient.id);

  async function declarerDeces() {
    const cause = window.prompt("Cause du décès (OBLIGATOIRE — rapport de mortalité SNIS) :");
    if (!cause?.trim()) return;
    const resultat = await declareDeath(patient.id, cause.trim());
    toast({
      title: resultat.status === "ok" ? "Décès déclaré — dossier scellé" : "Refusé",
      description: resultat.status === "ok"
        ? "Plus aucune consultation ni rendez-vous possible pour ce dossier."
        : "message" in resultat ? resultat.message : "Erreur",
      variant: resultat.status === "ok" ? "default" : "destructive",
    });
  }

  async function referer() {
    const destination = window.prompt(
      "Structure de destination (ex. CHU Yalgado Ouédraogo) :",
      "CHU Yalgado Ouédraogo",
    );
    if (!destination?.trim()) return;
    const motif = window.prompt("Motif de la référence (OBLIGATOIRE) :");
    if (!motif?.trim()) return;
    const resultat = await createReference({
      patientId: patient.id,
      structureDestination: destination.trim(),
      motif: motif.trim(),
    });
    if (resultat.status === "ok") {
      toast({ title: "Référence initiée", description: "La fiche suit la filière — vue Références." });
      goTo("references");
    } else {
      toast({ title: "Refusé", description: "message" in resultat ? resultat.message : "Erreur", variant: "destructive" });
    }
  }

  /** P1-8 — saisie du résultat de laboratoire (forward-only). */
  async function saisirResultat(examenId: string) {
    const texte = window.prompt("Résultat (OBLIGATOIRE — ex. « TDR positif », « Hb 11,2 g/dL ») :");
    if (!texte?.trim()) return;
    const positifBrut = window.prompt("Positif ? (oui / non / laisser vide si non applicable) :");
    const positif = positifBrut?.trim().toLowerCase() === "oui"
      ? true
      : positifBrut?.trim().toLowerCase() === "non"
        ? false
        : undefined;
    const resultat = await enregistrerResultatExamen(examenId, texte.trim(), positif);
    toast({
      title: resultat.status === "ok" ? "Résultat enregistré" : "Refusé",
      description: resultat.status === "ok"
        ? "Le diagnostic tient maintenant sa preuve — l'examen est forward-only."
        : "message" in resultat ? resultat.message : "Erreur",
      variant: resultat.status === "ok" ? "default" : "destructive",
    });
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Stethoscope className="h-4 w-4" /> Historique clinique ({duPatient.length})
        </CardTitle>
        {patient.deceased && (
          <Badge variant="secondary" className="bg-red-500/10 text-red-700 dark:text-red-400">
            <FileWarning className="mr-1 h-3 w-3" /> Décédé{patient.causeDeces ? ` · ${patient.causeDeces}` : ""}
          </Badge>
        )}
        {!patient.deceased && (
          <div className="flex flex-wrap gap-2">
            {peutReferer && (
              <Button size="sm" variant="outline" className="rounded-full" onClick={referer}>
                Référencer…
              </Button>
            )}
            {peutEcrire && (
              <Button size="sm" variant="ghost" className="rounded-full text-destructive" onClick={declarerDeces}>
                Déclarer un décès…
              </Button>
            )}
          </div>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        {duPatient.length === 0 && (
          <p className="text-sm text-muted-foreground">
            Aucune consultation enregistrée — l&apos;acte clinique complet se rédige
            dans la vue Consultation (motif, constantes, notes, diagnostic codé).
          </p>
        )}
        {duPatient.map((consult) => (
          <div key={consult.id} className="rounded-2xl border p-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="secondary" className="font-mono">{consult.diagnosticCode}</Badge>
              <span className="text-sm font-medium">{consult.diagnosticLabel}</span>
              <span className="text-xs text-muted-foreground">
                {new Date(consult.date).toLocaleString("fr-FR")} · {consult.practitioner}
              </span>
            </div>
            <p className="mt-1.5 text-sm">{consult.motif}</p>
            <div className="mt-2 flex flex-wrap gap-3 text-xs text-muted-foreground">
              {consult.constantes.taSystolique !== undefined && (
                <span className="flex items-center gap-1">
                  <Activity className="h-3 w-3" />
                  TA {consult.constantes.taSystolique}/{consult.constantes.taDiastolique}
                </span>
              )}
              {consult.constantes.temperatureC !== undefined && (
                <span>T° {consult.constantes.temperatureC} °C</span>
              )}
              {consult.constantes.poidsKg !== undefined && (
                <span>{consult.constantes.poidsKg} kg</span>
              )}
              {consult.examens.filter((e) => e.statut === "resultat").length > 0 && (
                <span className="font-medium text-emerald-600">
                  Labo : {consult.examens.filter((e) => e.statut === "resultat").length} résultat(s)
                </span>
              )}
            </div>
            {consult.notes && (
              <p className="mt-2 rounded-xl bg-muted/40 p-2.5 text-xs leading-relaxed">
                {consult.notes}
              </p>
            )}
            {/* P1-8 — examens de laboratoire embarqués : commande → résultat. */}
            {consult.examens.length > 0 && (
              <ul className="mt-2 space-y-1.5">
                {consult.examens.map((examen) => (
                  <li key={examen.id} className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 px-2.5 py-1.5 text-xs">
                    <FlaskConical className="h-3 w-3 text-muted-foreground" aria-hidden />
                    <span className="font-medium">{libelleTypeExamen(examen.type)}</span>
                    {examen.statut === "resultat" ? (
                      <span className={examen.positif ? "font-medium text-emerald-700" : "text-muted-foreground"}>
                        {examen.resultat}{examen.positif === true ? " (positif)" : examen.positif === false ? " (négatif)" : ""}
                      </span>
                    ) : (
                      <Badge className="bg-amber-100 text-amber-800">Résultat attendu</Badge>
                    )}
                    {examen.statut !== "resultat" && peutLabo && (
                      <Button
                        size="sm"
                        variant="outline"
                        className="ml-auto h-7"
                        onClick={() => void saisirResultat(examen.id)}
                      >
                        Saisir le résultat
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/** Libellé lisible d'un type d'examen (P1-8). */
function libelleTypeExamen(type: string): string {
  return TYPES_EXAMENS.find((t) => t.value === type)?.label ?? type;
}
