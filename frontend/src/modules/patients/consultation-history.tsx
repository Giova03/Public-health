"use client";

/**
 * Historique clinique V14 (I4/I15/I7) — les CONSULTATIONS persistées
 * (motif, constantes, diagnostic codé, notes), la déclaration de décès
 * et l'initiation de référence, directement dans la fiche patient.
 */

import { useEffect } from "react";
import { Activity, FileWarning, Stethoscope } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { Patient } from "@/lib/types";

export function ConsultationHistory({ patient }: { patient: Patient }) {
  const consultations = useAppStore((s) => s.consultations);
  const loadConsultations = useAppStore((s) => s.loadConsultations);
  const declareDeath = useAppStore((s) => s.declareDeath);
  const createReference = useAppStore((s) => s.createReference);
  const goTo = useAppStore((s) => s.goTo);
  const peutEcrire = usePermission("consultation:ecrire");
  const peutReferer = usePermission("reference:gerer");
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
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
