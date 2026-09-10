"use client";

/**
 * Module Patients (E1) — dialogue 409 « dossiers similaires ».
 *
 * LE contrat UX du MPI : un POST /patients qui répond 409 n'est pas un
 * échec, c'est le registre national qui demande une décision humaine.
 * Chaque candidat affiche son score de rapprochement (barre + %), son
 * verdict (Blocage / À examiner) et ses preuves. Deux issues :
 *  - ouvrir un dossier existant (la bonne décision par défaut) ;
 *  - créer malgré tout → motif OBLIGATOIRE, tracé en audit, avec
 *    re-soumission idempotente (même clientRequestId).
 */

import { useState } from "react";
import {
  AlertTriangle,
  Cake,
  ExternalLink,
  FileWarning,
  Loader2,
  MapPin,
  Phone,
} from "lucide-react";
import type {
  CreatePatientInput,
  DuplicateCandidate,
  Patient,
} from "@/lib/types";
import { useAppStore } from "@/lib/store";
import { useToast } from "@/hooks/use-toast";
import { formatDate } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { ageLabel, fullName, verdictClasses } from "./patient-utils";
import type { CreateStatus } from "./patient-form";

export interface ConflictState {
  /** Requête initiale — re-soumise à l'identique (idempotence). */
  input: CreatePatientInput;
  candidates: DuplicateCandidate[];
}

const MAX_CANDIDATES = 5;
const MIN_REASON_LENGTH = 5;

export interface DuplicatesDialogProps {
  conflict: ConflictState | null;
  /** false = annulation : le dialogue se ferme, le formulaire reste ouvert. */
  onOpenChange: (open: boolean) => void;
  /** Le clinicien reconnaît un dossier existant : on l'ouvre. */
  onOpenExisting: (patient: Patient) => void;
  /** Création forcée aboutie (created / replayed / queued). */
  onForcedCreated: (patient: Patient, status: CreateStatus) => void;
  /** Rare : la re-soumission forcée retombe sur un 409 → nouveaux candidats. */
  onConflictAgain: (candidates: DuplicateCandidate[]) => void;
}

export function DuplicatesDialog({
  conflict,
  onOpenChange,
  onOpenExisting,
  onForcedCreated,
  onConflictAgain,
}: DuplicatesDialogProps) {
  const createPatient = useAppStore((s) => s.createPatient);
  const { toast } = useToast();

  const [forceStep, setForceStep] = useState(false);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const open = conflict !== null;
  const candidates = (conflict?.candidates ?? []).slice(0, MAX_CANDIDATES);
  const trimmedReason = reason.trim();
  const reasonTooShort =
    trimmedReason.length > 0 && trimmedReason.length < MIN_REASON_LENGTH;
  const hasBlocking = candidates.some((c) => c.verdict === "BLOCKING");

  const handleForce = async () => {
    if (!conflict || trimmedReason.length < MIN_REASON_LENGTH) return;
    setSubmitting(true);
    // Même clientRequestId : la re-soumission reste idempotente.
    const result = await createPatient(conflict.input, {
      forceCreate: true,
      reason: trimmedReason,
    });
    setSubmitting(false);

    switch (result.status) {
      case "conflict":
        toast({
          title: "De nouveaux rapprochements détectés",
          description:
            "La vérification serveur a trouvé d'autres candidats — examinez-les.",
        });
        setForceStep(false);
        onConflictAgain(result.candidates);
        break;
      case "created":
      case "replayed":
      case "queued":
        setForceStep(false);
        setReason("");
        onForcedCreated(result.patient, result.status);
        break;
      case "error":
        toast({
          variant: "destructive",
          title: "Création impossible",
          description: result.message,
        });
        break;
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="top-[50%] grid max-h-[92dvh] w-full grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-2xl">
        <DialogHeader className="space-y-2 px-5 pt-6 pb-4 text-left sm:px-6">
          <DialogTitle className="flex items-center gap-2.5">
            <span
              className="flex h-8 w-8 items-center justify-center rounded-full border border-amber-500/40 bg-amber-500/10 text-amber-600 dark:text-amber-400"
              aria-hidden="true"
            >
              <AlertTriangle className="h-4 w-4" />
            </span>
            Dossiers similaires détectés
          </DialogTitle>
          <DialogDescription>
            Examinez ces dossiers avant de continuer&nbsp;: choisir un dossier
            existant évite un doublon dans le registre national des patients.
          </DialogDescription>
        </DialogHeader>

        {/* Candidats */}
        <div className="max-h-[52dvh] space-y-3 overflow-y-auto scrollbar-thin px-5 pb-4 sm:max-h-[46dvh] sm:px-6">
          {candidates.map((candidate) => {
            const pct = Math.round(candidate.score * 100);
            const tone = verdictClasses(candidate.verdict);
            return (
              <article
                key={candidate.patient.id}
                className="rounded-xl border bg-card p-4 shadow-xs"
              >
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold">
                      {fullName(candidate.patient.name)}
                    </p>
                    <p className="truncate font-mono text-[11px] text-muted-foreground">
                      {candidate.patient.phReference || "réf. en attente"}
                    </p>
                  </div>
                  {candidate.verdict === "BLOCKING" ? (
                    <Badge variant="destructive">Blocage</Badge>
                  ) : (
                    <Badge variant="outline" className={tone.badge}>
                      À examiner
                    </Badge>
                  )}
                </div>

                <dl className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
                  <div className="flex items-center gap-1">
                    <Cake className="h-3.5 w-3.5" aria-hidden="true" />
                    <dd className="tnum">
                      {formatDate(candidate.patient.birthDate)} ·{" "}
                      {ageLabel(candidate.patient.birthDate)}
                    </dd>
                  </div>
                  {candidate.patient.phone && (
                    <div className="flex items-center gap-1">
                      <Phone className="h-3.5 w-3.5" aria-hidden="true" />
                      <dd className="tnum">{candidate.patient.phone}</dd>
                    </div>
                  )}
                  <div className="flex items-center gap-1">
                    <MapPin className="h-3.5 w-3.5" aria-hidden="true" />
                    <dd className="truncate">{candidate.patient.facility}</dd>
                  </div>
                </dl>

                {/* Score de rapprochement */}
                <div className="mt-3">
                  <div className="mb-1 flex items-center justify-between text-[11px] text-muted-foreground">
                    <span>Score de rapprochement</span>
                    <span
                      className={cn(
                        "tnum font-semibold",
                        candidate.verdict === "BLOCKING"
                          ? "text-destructive"
                          : "text-amber-700 dark:text-amber-400",
                      )}
                    >
                      {pct}&nbsp;%
                    </span>
                  </div>
                  <div
                    role="progressbar"
                    aria-label={`Score de rapprochement : ${pct} %`}
                    aria-valuenow={pct}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    className="h-1.5 overflow-hidden rounded-full bg-muted"
                  >
                    <div
                      className={cn(
                        "h-full rounded-full transition-[width] duration-300",
                        tone.bar,
                      )}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                </div>

                {/* Preuves */}
                {candidate.reasons.length > 0 && (
                  <ul className="mt-2.5 flex flex-wrap gap-1.5">
                    {candidate.reasons.map((r) => (
                      <li key={r}>
                        <Badge
                          variant="secondary"
                          className="text-[11px] font-normal"
                        >
                          {r}
                        </Badge>
                      </li>
                    ))}
                  </ul>
                )}

                <Button
                  type="button"
                  variant="outline"
                  className="mt-3 min-h-11 w-full"
                  onClick={() => onOpenExisting(candidate.patient)}
                >
                  <ExternalLink className="h-4 w-4" aria-hidden="true" />
                  Ouvrir ce dossier
                </Button>
              </article>
            );
          })}
        </div>

        {/* Décision globale */}
        <div className="border-t bg-muted/40 px-5 py-4 sm:px-6">
          {!forceStep ? (
            <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-end">
              <p className="flex-1 text-xs text-muted-foreground">
                {candidates.length} candidat{candidates.length > 1 ? "s" : ""}
                {hasBlocking
                  ? " — dont un rapprochement à probabilité élevée."
                  : " à examiner."}{" "}
                Annuler pour corriger la saisie.
              </p>
              <Button
                type="button"
                variant="ghost"
                className="min-h-11"
                onClick={() => onOpenChange(false)}
              >
                Annuler
              </Button>
              <Button
                type="button"
                variant="outline"
                className="min-h-11 sm:px-5"
                onClick={() => setForceStep(true)}
              >
                <FileWarning className="h-4 w-4" aria-hidden="true" />
                Créer quand même un nouveau dossier
              </Button>
            </div>
          ) : (
            <div className="space-y-3">
              <div className="space-y-2">
                <p className="text-sm font-medium">
                  Motif de création forcée <span className="text-destructive">*</span>
                </p>
                <Textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="J'ai vérifié, il s'agit d'une autre personne : …"
                  rows={3}
                  className="min-h-20 bg-background"
                  aria-label="Motif obligatoire de la création forcée"
                />
                <p
                  className={cn(
                    "text-xs",
                    reasonTooShort ? "text-destructive" : "text-muted-foreground",
                  )}
                >
                  Motif obligatoire ({MIN_REASON_LENGTH} caractères minimum) —
                  tracé en audit, immuable.
                </p>
              </div>
              <div className="flex flex-col-reverse gap-2.5 sm:flex-row sm:justify-end">
                <Button
                  type="button"
                  variant="ghost"
                  className="min-h-11"
                  onClick={() => setForceStep(false)}
                  disabled={submitting}
                >
                  Retour aux candidats
                </Button>
                <Button
                  type="button"
                  variant="medical"
                  className="min-h-11 px-5"
                  disabled={submitting || trimmedReason.length < MIN_REASON_LENGTH}
                  onClick={() => void handleForce()}
                >
                  {submitting ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                      Vérification…
                    </>
                  ) : (
                    <>
                      <FileWarning className="h-4 w-4" aria-hidden="true" />
                      Confirmer la création
                    </>
                  )}
                </Button>
              </div>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
