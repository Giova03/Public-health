"use client";

import { FlaskConical, HeartPulse, NotebookPen, Scale, Stethoscope, Thermometer } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { DIAGNOSES } from "@/lib/demo/reference";
import { TYPES_EXAMENS } from "@/lib/types";
import { OTHER_DIAGNOSIS, type ExamDraft } from "../helpers";

/**
 * Étape 2 — Examen.
 * Motif, diagnostic (référentiel ou « Autre… ») et constantes optionnelles.
 * Les constantes sont purement démonstratives : elles n'alimentent pas
 * l'API, elles servent au récapitulatif de l'étape Ordonnance.
 */

interface ExamStepProps {
  exam: ExamDraft;
  onChange: (patch: Partial<ExamDraft>) => void;
}

export function ExamStep({ exam, onChange }: ExamStepProps) {
  const isOther = exam.diagnosisChoice === OTHER_DIAGNOSIS;

  return (
    <div className="space-y-5">
      <div className="space-y-2">
        <Label htmlFor="consult-motif" className="text-sm">
          Motif de consultation
        </Label>
        <Textarea
          id="consult-motif"
          value={exam.motif}
          onChange={(e) => onChange({ motif: e.target.value })}
          rows={2}
          placeholder="Fièvre depuis 2 jours, céphalées, frissons…"
          className="min-h-[56px]"
        />
      </div>

      <div className="space-y-2">
        <Label className="text-sm">
          <Stethoscope
            className="mr-1.5 h-3.5 w-3.5 text-muted-foreground"
            aria-hidden="true"
          />
          Diagnostic
        </Label>
        <Select
          value={exam.diagnosisChoice || undefined}
          onValueChange={(v) => onChange({ diagnosisChoice: v })}
        >
          <SelectTrigger className="h-11 w-full" aria-label="Diagnostic">
            <SelectValue placeholder="Choisir un diagnostic" />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectLabel>Référentiel national</SelectLabel>
              {DIAGNOSES.map((d) => (
                <SelectItem key={d} value={d} className="min-h-11">
                  {d}
                </SelectItem>
              ))}
            </SelectGroup>
            <SelectSeparator />
            <SelectItem value={OTHER_DIAGNOSIS} className="min-h-11">
              Autre…
            </SelectItem>
          </SelectContent>
        </Select>
        {isOther && (
          <Input
            value={exam.diagnosisOther}
            onChange={(e) => onChange({ diagnosisOther: e.target.value })}
            placeholder="Précisez le diagnostic libre"
            className="h-11"
            aria-label="Diagnostic libre"
          />
        )}
        <p className="text-xs text-muted-foreground">
          Obligatoire pour valider la consultation.
        </p>
      </div>

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium text-muted-foreground">
          Constantes (optionnel — non transmises à l&rsquo;API)
        </legend>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <div className="col-span-2 space-y-2 sm:col-span-1">
            <Label
              htmlFor="consult-ta"
              className="flex items-center gap-1.5 text-sm"
            >
              <HeartPulse
                className="h-3.5 w-3.5 text-muted-foreground"
                aria-hidden="true"
              />
              Tension artérielle
            </Label>
            <Input
              id="consult-ta"
              value={exam.bloodPressure}
              onChange={(e) => onChange({ bloodPressure: e.target.value })}
              placeholder="120/80"
              inputMode="text"
              className="h-11"
              autoComplete="off"
            />
          </div>
          <div className="space-y-2">
            <Label
              htmlFor="consult-temp"
              className="flex items-center gap-1.5 text-sm"
            >
              <Thermometer
                className="h-3.5 w-3.5 text-muted-foreground"
                aria-hidden="true"
              />
              Température (°C)
            </Label>
            <Input
              id="consult-temp"
              value={exam.temperature}
              onChange={(e) => onChange({ temperature: e.target.value })}
              type="number"
              min={30}
              max={45}
              step={0.1}
              placeholder="37.5"
              inputMode="decimal"
              className="h-11 tnum"
            />
          </div>
          <div className="space-y-2">
            <Label
              htmlFor="consult-poids"
              className="flex items-center gap-1.5 text-sm"
            >
              <Scale
                className="h-3.5 w-3.5 text-muted-foreground"
                aria-hidden="true"
              />
              Poids (kg)
            </Label>
            <Input
              id="consult-poids"
              value={exam.weight}
              onChange={(e) => onChange({ weight: e.target.value })}
              type="number"
              min={0}
              max={300}
              step={0.1}
              placeholder="62"
              inputMode="decimal"
              className="h-11 tnum"
            />
          </div>
        </div>
      </fieldset>

      <div className="space-y-2">
        <Label
          htmlFor="consult-notes"
          className="flex items-center gap-1.5 text-sm"
        >
          <NotebookPen
            className="h-3.5 w-3.5 text-muted-foreground"
            aria-hidden="true"
          />
          Notes
        </Label>
        <Textarea
          id="consult-notes"
          value={exam.notes}
          onChange={(e) => onChange({ notes: e.target.value })}
          rows={3}
          placeholder="Observations, antécédents, conduite à tenir…"
        />
      </div>

      {/* P1-8 — examens de laboratoire : la preuve derrière le diagnostic. */}
      <fieldset className="rounded-lg border bg-muted/30 p-3">
        <legend className="px-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Laboratoire — examens à commander
        </legend>
        <div className="grid grid-cols-2 gap-2">
          {TYPES_EXAMENS.slice(0, 4).map((examen) => {
            const coche = exam.examensCommandes.includes(examen.value);
            return (
              <label
                key={examen.value}
                className="flex min-h-11 cursor-pointer items-center gap-2 rounded-md border bg-card px-3 py-2 text-sm"
              >
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-primary"
                  checked={coche}
                  onChange={(e) =>
                    onChange({
                      examensCommandes: e.target.checked
                        ? [...exam.examensCommandes, examen.value]
                        : exam.examensCommandes.filter((t) => t !== examen.value),
                    })
                  }
                />
                <FlaskConical className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
                {examen.label}
              </label>
            );
          })}
        </div>
        <p className="mt-2 text-xs text-muted-foreground">
          La commande part avec l&apos;acte clinique — le résultat se saisit
          dans le dossier patient (forward-only : jamais réécrit).
        </p>
      </fieldset>
    </div>
  );
}
