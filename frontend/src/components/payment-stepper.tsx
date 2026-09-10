import { Check, Circle, MoveRight } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * PaymentStateStepper — les 8 états du paiement, visualisés.
 * Règle métier : forward-only, aucun état ne recule jamais,
 * la réconciliation (23 h) fait foi.
 */
export const PAYMENT_STATES = [
  "INITIATED",
  "PENDING",
  "AUTHORIZED",
  "SUCCEEDED",
  "RECONCILED",
] as const;

export const PAYMENT_BRANCHES = ["FAILED", "CANCELLED", "REFUNDED"] as const;

interface PaymentStepperProps {
  /** État courant surligné */
  current?: (typeof PAYMENT_STATES)[number];
  className?: string;
}

const LABELS: Record<string, string> = {
  INITIATED: "Initié",
  PENDING: "En cours",
  AUTHORIZED: "Autorisé",
  SUCCEEDED: "Reçu",
  RECONCILED: "Réconcilié",
  FAILED: "Échec",
  CANCELLED: "Annulé",
  REFUNDED: "Remboursé",
};

export function PaymentStepper({
  current = "INITIATED",
  className,
}: PaymentStepperProps) {
  const currentIndex = PAYMENT_STATES.indexOf(current);

  return (
    <div className={cn("space-y-3", className)} aria-label="États du paiement">
      <ol className="flex flex-wrap items-center gap-1.5">
        {PAYMENT_STATES.map((state, index) => {
          const done = index < currentIndex;
          const active = index === currentIndex;
          return (
            <li key={state} className="flex items-center gap-1.5">
              <span
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium",
                  done && "border-primary/40 bg-primary/10 text-primary",
                  active &&
                    "border-primary bg-primary text-primary-foreground shadow-sm",
                  !done &&
                    !active &&
                    "border-border bg-muted/50 text-muted-foreground",
                )}
                aria-current={active ? "step" : undefined}
              >
                {done ? (
                  <Check className="h-3 w-3" aria-hidden="true" />
                ) : (
                  <Circle className="h-3 w-3" aria-hidden="true" />
                )}
                {LABELS[state]}
              </span>
              {index < PAYMENT_STATES.length - 1 && (
                <MoveRight
                  className="h-3.5 w-3.5 text-muted-foreground/60"
                  aria-hidden="true"
                />
              )}
            </li>
          );
        })}
      </ol>
      <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        Branches exceptionnelles :
        {PAYMENT_BRANCHES.map((state) => (
          <span
            key={state}
            className="inline-flex items-center rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground"
          >
            {LABELS[state]}
          </span>
        ))}
        <span className="ml-1">— transitions irréversibles uniquement</span>
      </p>
    </div>
  );
}
