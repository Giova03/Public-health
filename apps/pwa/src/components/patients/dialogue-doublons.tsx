"use client";

import { useState } from "react";
import { TriangleAlert, UserCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import type { CandidatDoublon } from "@/lib/api/client";
import { formaterDate, libelleSexe } from "@/lib/format";

interface DialogueDoublonsProps {
  ouvert: boolean;
  onOuvertChange: (ouvert: boolean) => void;
  /** Candidats renvoyés par le 409 (déjà triés par score décroissant). */
  candidats: CandidatDoublon[];
  /** Une requête « créer quand même » est en vol. */
  enCours: boolean;
  /** L'agent reconnaît un candidat → ouvrir son dossier. */
  onChoisirCandidat: (patientId: string) => void;
  /** L'agent tranche : patient distinct, motif obligatoire. */
  onCreerQuandMeme: (motif: string) => void;
}

/** Verdict dérivé du contrat : blocking (ou score ≥ 0,90) = quasi certain. */
function estBloquant(candidat: CandidatDoublon): boolean {
  return candidat.blocking || candidat.score >= 0.9;
}

function BadgeVerdict({ candidat }: { candidat: CandidatDoublon }) {
  const bloquant = estBloquant(candidat);
  return (
    <Badge
      variant="outline"
      role="status"
      aria-label={
        bloquant
          ? `Doublon probable, score ${Math.round(candidat.score * 100)} %`
          : `À vérifier, score ${Math.round(candidat.score * 100)} %`
      }
      className={
        bloquant
          ? "border-red-300 bg-red-50 text-red-800"
          : "border-amber-300 bg-amber-50 text-amber-800"
      }
    >
      {bloquant ? "Doublon probable" : "À vérifier"}
      <span className="tabular-nums font-normal">
        {Math.round(candidat.score * 100)} %
      </span>
    </Badge>
  );
}

/**
 * Dialogue des doublons — LE contrat UX de l'épique E1 (ADR-003).
 * Le 409 n'est pas une erreur : c'est la machine qui rend la décision
 * à l'humain. Chaque candidat est montré avec son score ; l'agent peut
 * reconnaître le patient OU créer un dossier distinct avec un motif
 * tracé (jamais de fusion ni de création en silence).
 */
export function DialogueDoublons({
  ouvert,
  onOuvertChange,
  candidats,
  enCours,
  onChoisirCandidat,
  onCreerQuandMeme,
}: DialogueDoublonsProps) {
  const [motif, setMotif] = useState("");
  const [erreurMotif, setErreurMotif] = useState<string | null>(null);

  const validerMotif = (): boolean => {
    const net = motif.trim();
    if (net.length < 10) {
      setErreurMotif(
        "Motif obligatoire (10 caractères minimum) : il sera tracé dans le registre.",
      );
      return false;
    }
    setErreurMotif(null);
    return true;
  };

  const confirmerCreation = () => {
    if (!validerMotif()) return;
    onCreerQuandMeme(motif.trim());
  };

  return (
    <Dialog open={ouvert} onOpenChange={onOuvertChange}>
      <DialogContent className="max-h-[90vh] gap-0 overflow-y-auto p-0 sm:max-w-lg">
        <DialogHeader className="border-b border-border p-4 sm:p-6">
          <DialogTitle className="flex items-start gap-2.5 text-left text-base sm:text-lg">
            <TriangleAlert
              className="mt-0.5 h-5 w-5 shrink-0 text-amber-600"
              aria-hidden="true"
            />
            Patient probablement déjà enregistré
          </DialogTitle>
          <DialogDescription className="text-left">
            Le registre national contient {candidats.length} dossier
            {candidats.length > 1 ? "s" : ""} proche
            {candidats.length > 1 ? "s" : ""} de celui saisi. Vérifiez la liste
            : créer un doublon fragmente le dossier médical du patient.
          </DialogDescription>
        </DialogHeader>

        <ul className="divide-y divide-border">
          {candidats.map((candidat) => (
            <li
              key={candidat.id}
              className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:justify-between sm:gap-4"
            >
              <div className="min-w-0 leading-tight">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="truncate text-sm font-semibold">
                    {candidat.family} {candidat.given}
                  </p>
                  <BadgeVerdict candidat={candidat} />
                </div>
                <p className="mt-1.5 font-mono text-xs text-muted-foreground">
                  {candidat.phReference}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  Né(e) le {formaterDate(candidat.birthDate)} ·{" "}
                  {libelleSexe(candidat.gender)}
                  {candidat.method === "EXACT" && " · preuve exacte"}
                </p>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-10 w-full shrink-0 sm:w-auto"
                onClick={() => onChoisirCandidat(candidat.id)}
              >
                <UserCheck aria-hidden="true" />
                C&apos;est ce patient
              </Button>
            </li>
          ))}
        </ul>

        <DialogFooter className="flex-col items-stretch gap-3 border-t border-border p-4 sm:p-6">
          <div className="space-y-2">
            <label
              htmlFor="motif-creation-forcee"
              className="text-xs font-medium text-foreground"
            >
              Motif de création malgré le doublon (obligatoire, tracé à des fins
              d&apos;audit)
            </label>
            <Textarea
              id="motif-creation-forcee"
              value={motif}
              onChange={(event) => {
                setMotif(event.target.value);
                if (erreurMotif) setErreurMotif(null);
              }}
              aria-invalid={erreurMotif ? true : undefined}
              aria-describedby={erreurMotif ? "erreur-motif-creation" : undefined}
              placeholder="Ex. : homonymie confirmée, dates de naissance différentes vérifiées sur la CNIB…"
              className="min-h-20 text-sm"
              maxLength={500}
            />
            {erreurMotif && (
              <p
                id="erreur-motif-creation"
                role="alert"
                className="text-xs text-destructive"
              >
                {erreurMotif}
              </p>
            )}
          </div>
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button
              type="button"
              variant="outline"
              disabled={enCours}
              onClick={() => onOuvertChange(false)}
            >
              Annuler
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={enCours}
              onClick={confirmerCreation}
            >
              {enCours ? "Envoi…" : "Créer quand même"}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
