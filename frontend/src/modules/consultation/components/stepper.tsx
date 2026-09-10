"use client";

import { Check } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * ConsultationStepper — progression guidée en 3 étapes (discrète).
 * Les étapes franchissables restent cliquables pour revenir en arrière ;
 * l'étape active porte `aria-current="step"`.
 */

const STEP_LABELS = ["Patient", "Examen", "Ordonnance"] as const;

interface ConsultationStepperProps {
  current: number;
  /** Étape i considérée comme achevée (patient choisi, diagnostic posé…). */
  completed: boolean[];
  onStep: (index: number) => void;
}

export function ConsultationStepper({
  current,
  completed,
  onStep,
}: ConsultationStepperProps) {
  return (
    <ol
      className="flex items-center gap-1"
      aria-label="Étapes de la consultation"
    >
      {STEP_LABELS.map((label, i) => {
        const done = completed[i] && i !== current;
        const active = i === current;
        const reachable = i <= current || completed[i];
        return (
          <li key={label} className="flex min-w-0 flex-1 items-center gap-1">
            <button
              type="button"
              disabled={!reachable}
              onClick={() => onStep(i)}
              aria-current={active ? "step" : undefined}
              className={cn(
                "flex min-h-[44px] flex-1 items-center justify-center gap-2 rounded-xl px-2 text-xs font-medium sm:text-sm",
                "outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed",
                active && "bg-primary/10 text-primary",
                done && "text-primary/80 hover:bg-accent",
                !active && !done && "text-muted-foreground",
                !reachable && "opacity-60",
              )}
            >
              <span
                className={cn(
                  "flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-[10px] font-semibold tnum",
                  active && "border-primary bg-primary text-primary-foreground",
                  done && "border-emerald-600 bg-emerald-600 text-white dark:border-emerald-500 dark:bg-emerald-500",
                  !active && !done && "border-border text-muted-foreground",
                )}
              >
                {done ? (
                  <Check className="h-3 w-3" aria-hidden="true" />
                ) : (
                  i + 1
                )}
              </span>
              <span className="truncate">{label}</span>
            </button>
            {i < STEP_LABELS.length - 1 && (
              <span
                className="h-px w-3 shrink-0 bg-border sm:w-5"
                aria-hidden="true"
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}
