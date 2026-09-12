"use client";

import { Package, Plus, TriangleAlert, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { DRUGS } from "@/lib/demo/reference";
import { formatXof } from "@/lib/types";
import {
  dosesPerDay,
  drugByDci,
  FREQUENCIES,
  type DraftLine,
} from "../helpers";
import { cn } from "@/lib/utils";

/**
 * Étape 3 — lignes d'ordonnance (éditeur dynamique).
 *
 * Chaque ligne : médicament (référentiel DCI — forme), dosage prérempli
 * depuis la forme, fréquence, durée, quantité suggérée
 * (doses/jour × durée, éditable) et repères de stock/coût.
 * Un même DCI ne peut figurer qu'une fois (la dispensation cumulée
 * du domaine E3 est par médicament).
 */

interface LinesEditorProps {
  lines: DraftLine[];
  onUpdateLine: (index: number, patch: Partial<DraftLine>) => void;
  onRemoveLine: (index: number) => void;
  onAddLine: () => void;
}

/** Champ numérique contrôlé : 0 s'affiche vide, saisie vidée → 0. */
function numberValue(n: number): string {
  return n > 0 ? String(n) : "";
}

function toNumber(raw: string): number {
  if (raw.trim() === "") return 0;
  const parsed = Number(raw.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

export function LinesEditor({
  lines,
  onUpdateLine,
  onRemoveLine,
  onAddLine,
}: LinesEditorProps) {
  return (
    <div className="space-y-3">
      {lines.length === 0 && (
        <p
          role="status"
          className="rounded-xl border border-dashed p-4 text-sm text-muted-foreground"
        >
          Aucun médicament — ajoutez au moins une ligne pour valider
          l&rsquo;ordonnance.
        </p>
      )}

      <ul className="space-y-3">
        {lines.map((line, index) => {
          const reference = line.drug ? drugByDci(line.drug) : undefined;
          const quantity = line.quantity;
          const invalidQuantity = quantity < 1;
          const invalidDuration = line.durationDays < 1;
          const overStock =
            reference !== undefined && quantity > reference.stock;
          return (
            <li
              key={index}
              className="space-y-3 rounded-xl border p-4"
              aria-label={`Ligne de l'ordonnance ${index + 1}`}
            >
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                  Médicament {index + 1}
                </p>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-11 w-11 text-muted-foreground hover:text-destructive"
                  onClick={() => onRemoveLine(index)}
                  aria-label={`Retirer le médicament ${index + 1}`}
                  disabled={lines.length === 0}
                >
                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                </Button>
              </div>

              <div className="space-y-2">
                <Label className="text-sm" htmlFor={`drug-${index}`}>
                  Médicament (liste nationale)
                </Label>
                <Select
                  value={line.drug || undefined}
                  onValueChange={(v) => onUpdateLine(index, { drug: v })}
                >
                  <SelectTrigger
                    id={`drug-${index}`}
                    className="h-11 w-full"
                    aria-label={`Médicament de la ligne ${index + 1}`}
                  >
                    <SelectValue placeholder="Choisir un médicament" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectLabel>DCI — forme</SelectLabel>
                      {DRUGS.map((d) => {
                        const alreadyUsed = lines.some(
                          (l, j) => j !== index && l.drug === d.dci,
                        );
                        return (
                          <SelectItem
                            key={d.dci}
                            value={d.dci}
                            disabled={alreadyUsed}
                            className="min-h-11"
                          >
                            {d.dci} — {d.form}
                          </SelectItem>
                        );
                      })}
                    </SelectGroup>
                  </SelectContent>
                </Select>
                {line.drug === "" && (
                  <p className="text-xs text-destructive">
                    Choisissez un médicament pour cette ligne.
                  </p>
                )}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor={`dosage-${index}`} className="text-sm">
                    Dosage
                  </Label>
                  <Input
                    id={`dosage-${index}`}
                    value={line.dosage}
                    onChange={(e) =>
                      onUpdateLine(index, {
                        dosage: e.target.value,
                        dosageTouched: true,
                      })
                    }
                    placeholder="ex. 500 mg"
                    className="h-11"
                    autoComplete="off"
                  />
                </div>
                <div className="space-y-2">
                  <Label
                    htmlFor={`frequency-${index}`}
                    className="text-sm"
                  >
                    Fréquence
                  </Label>
                  <Select
                    value={line.frequency}
                    onValueChange={(v) =>
                      onUpdateLine(index, { frequency: v })
                    }
                  >
                    <SelectTrigger
                      id={`frequency-${index}`}
                      className="h-11 w-full"
                      aria-label={`Fréquence de la ligne ${index + 1}`}
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {FREQUENCIES.map((f) => (
                        <SelectItem key={f} value={f} className="min-h-11">
                          {f}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor={`duration-${index}`} className="text-sm">
                    Durée (jours)
                  </Label>
                  <Input
                    id={`duration-${index}`}
                    type="number"
                    min={1}
                    step={1}
                    inputMode="numeric"
                    value={numberValue(line.durationDays)}
                    onChange={(e) =>
                      onUpdateLine(index, {
                        durationDays: Math.floor(toNumber(e.target.value)),
                      })
                    }
                    className="h-11 tnum"
                    aria-invalid={invalidDuration}
                  />
                  {invalidDuration && (
                    <p className="text-xs text-destructive">
                      Durée invalide (≥ 1 jour).
                    </p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor={`quantity-${index}`} className="text-sm">
                    Quantité
                  </Label>
                  <Input
                    id={`quantity-${index}`}
                    type="number"
                    min={1}
                    step={1}
                    inputMode="numeric"
                    value={numberValue(quantity)}
                    onChange={(e) =>
                      onUpdateLine(index, {
                        quantity: Math.round(toNumber(e.target.value)),
                      })
                    }
                    className="h-11 tnum"
                    aria-invalid={invalidQuantity}
                  />
                  <p
                    className={cn(
                      "text-xs",
                      invalidQuantity
                        ? "text-destructive"
                        : "text-muted-foreground",
                    )}
                  >
                    {line.quantityTouched ? (
                      "Quantité ajustée manuellement."
                    ) : (
                      <>
                        Suggestion&nbsp;:{" "}
                        <span className="tnum">
                          {dosesPerDay(line.frequency)}×/jour ×{" "}
                          {line.durationDays} j ={" "}
                          {dosesPerDay(line.frequency) * line.durationDays}
                        </span>
                      </>
                    )}
                  </p>
                </div>
              </div>

              {reference !== undefined && quantity >= 1 && (
                <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs text-muted-foreground">
                  <span className="inline-flex items-center gap-1.5">
                    <Package className="h-3.5 w-3.5" aria-hidden="true" />
                    Stock&nbsp;:{" "}
                    <span className="tnum">{reference.stock}</span>
                  </span>
                  <span className="tnum">
                    ≈ {formatXof(reference.unitPriceXof * quantity)}
                  </span>
                  {overStock && (
                    <span
                      role="alert"
                      className="inline-flex w-full items-center gap-1.5 text-amber-700 dark:text-amber-400"
                    >
                      <TriangleAlert className="h-3.5 w-3.5" aria-hidden="true" />
                      Quantité supérieure au stock disponible.
                    </span>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>

      <Button
        variant="outline"
        className="h-11 w-full border-dashed"
        onClick={onAddLine}
        disabled={lines.length >= DRUGS.length}
      >
        <Plus className="h-4 w-4" aria-hidden="true" />
        Ajouter un médicament
      </Button>
    </div>
  );
}
