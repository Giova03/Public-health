"use client";

import { useMemo, useState } from "react";
import { Loader2, Package, Pill } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import type { DispenseLine, Prescription } from "@/lib/types";
import { useCurrentUser } from "@/lib/session";
import { remainingOf } from "./prescription-helpers";
import { cn } from "@/lib/utils";

/**
 * Dialogue de dispensation partielle (E3).
 *
 * Le pharmacien saisit les quantités réellement remises, ligne par ligne,
 * plafonnées au reste prescrit (la garde cumul fait foi côté domaine) ;
 * la source distingue pharmacie de la structure et officine privée.
 */

interface DispenseDialogProps {
  prescription: Prescription | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (lines: DispenseLine[], source: "INTERNE" | "PRIVE") => Promise<void>;
}

export function DispenseDialog({
  prescription,
  open,
  onOpenChange,
  onConfirm,
}: DispenseDialogProps) {
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [source, setSource] = useState<"INTERNE" | "PRIVE">("INTERNE");
  const [submitting, setSubmitting] = useState(false);
  const user = useCurrentUser();

  const rows = useMemo(() => {
    if (!prescription) return [];
    return prescription.items
      .map((item) => ({
        drug: item.drug,
        dosage: item.dosage,
        remaining: remainingOf(prescription, item.drug, item.quantity),
      }))
      .filter((row) => row.remaining > 0);
  }, [prescription]);

  const selectedLines = rows
    .filter((row) => (quantities[row.drug] ?? 0) > 0)
    .map((row) => ({ drug: row.drug, quantity: quantities[row.drug] }));

  const invalid = rows.some(
    (row) => (quantities[row.drug] ?? 0) > row.remaining,
  );

  const reset = () => {
    setQuantities({});
    setSource("INTERNE");
    setSubmitting(false);
  };

  const confirm = async () => {
    if (!prescription || selectedLines.length === 0 || invalid) return;
    setSubmitting(true);
    await onConfirm(selectedLines, source);
    reset();
    onOpenChange(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!submitting) {
          if (!next) reset();
          onOpenChange(next);
        }
      }}
    >
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <span
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
              aria-hidden="true"
            >
              <Pill className="h-4 w-4" />
            </span>
            Dispenser — {prescription?.patientName
              ? `${prescription.patientName.family} ${prescription.patientName.given}`
              : ""}
          </DialogTitle>
          <DialogDescription>
            Dispensation partielle : les quantités sont plafonnées au reste
            prescrit. Le cumul est contrôlé par le domaine (aucun
            sur-dispensation possible).
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <ul className="space-y-3">
            {rows.length === 0 && (
              <li className="rounded-xl border border-dashed p-3 text-sm text-muted-foreground">
                Rien à dispenser : l&apos;ordonnance est soldée.
              </li>
            )}
            {rows.map((row) => (
              <li
                key={row.drug}
                className="flex items-center justify-between gap-3 rounded-xl border p-3"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{row.drug}</p>
                  <p className="text-xs text-muted-foreground tnum">
                    {row.dosage} · reste {row.remaining}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Label htmlFor={`qty-${row.drug}`} className="sr-only">
                    Quantité dispensée pour {row.drug}
                  </Label>
                  <Input
                    id={`qty-${row.drug}`}
                    type="number"
                    min={0}
                    max={row.remaining}
                    step={1}
                    inputMode="numeric"
                    value={quantities[row.drug] ?? 0}
                    onChange={(e) =>
                      setQuantities((q) => ({
                        ...q,
                        [row.drug]: Math.max(
                          0,
                          Math.round(Number(e.target.value || 0)),
                        ),
                      }))
                    }
                    className="h-11 w-24 tnum"
                    aria-invalid={
                      (quantities[row.drug] ?? 0) > row.remaining
                    }
                  />
                </div>
              </li>
            ))}
          </ul>

          <div className="space-y-2">
            <Label className="text-sm">Source de la dispensation</Label>
            <RadioGroup
              value={source}
              onValueChange={(v) => setSource(v as "INTERNE" | "PRIVE")}
              className="grid grid-cols-2 gap-3"
            >
              {(
                [
                  { value: "INTERNE", label: "Pharmacie de la structure" },
                  { value: "PRIVE", label: "Officine privée" },
                ] as const
              ).map((option) => (
                <Label
                  key={option.value}
                  className={cn(
                    "flex min-h-[52px] cursor-pointer items-center gap-2.5 rounded-xl border p-3 text-sm font-normal",
                    source === option.value && "border-primary bg-primary/5",
                  )}
                >
                  <RadioGroupItem value={option.value} />
                  {option.label}
                </Label>
              ))}
            </RadioGroup>
          </div>

          <p className="text-xs text-muted-foreground">
            Pharmacien : <span className="font-medium">{user.fullName}</span>
          </p>
        </div>

        <DialogFooter className="flex-col gap-2 sm:flex-row">
          <Button
            variant="outline"
            className="h-11 w-full sm:w-auto"
            onClick={() => onOpenChange(false)}
            disabled={submitting}
          >
            Annuler
          </Button>
          <Button
            className="h-11 w-full sm:w-auto"
            onClick={confirm}
            disabled={selectedLines.length === 0 || invalid || submitting}
          >
            {submitting ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
            ) : (
              <Package className="h-4 w-4" aria-hidden="true" />
            )}
            Dispenser {selectedLines.length > 0 ? `(${selectedLines.length})` : ""}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
