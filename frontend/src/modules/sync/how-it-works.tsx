"use client";

/**
 * Module Synchronisation (E2) — section pédagogique « Comment ça marche » :
 * les trois mécanismes du protocole offline-first (miroir IndexedDB, outbox
 * opId UUID v7, delta par curseur + idempotence). Pliée par défaut pour
 * garder le poste de commandement concentré sur l'état réel.
 */

import { ArrowLeftRight, ChevronRight, Database, Info, Inbox } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

interface Mechanic {
  icon: LucideIcon;
  title: string;
  text: string;
}

const MECHANICS: Mechanic[] = [
  {
    icon: Database,
    title: "Miroir (IndexedDB)",
    text: "Les dossiers consultés sont copiés sur l'appareil : la recherche et la fiche patient marchent sans réseau.",
  },
  {
    icon: Inbox,
    title: "Outbox",
    text: "Chaque création hors ligne reçoit un identifiant opId (UUID v7). Rien n'est perdu, tout est rejouable.",
  },
  {
    icon: ArrowLeftRight,
    title: "Delta + idempotence",
    text: "Au retour du réseau, le lot part au serveur (rejeu sûr par opId), puis les changements serveur reviennent par curseur. Un accusé perdu ne crée jamais de doublon.",
  },
];

export function HowItWorksCard() {
  return (
    <Card className="py-0">
      <details className="group">
        <summary className="flex cursor-pointer select-none list-none items-center justify-between gap-2 px-4 py-4 sm:px-6 [&::-webkit-details-marker]:hidden">
          <span className="flex items-center gap-2 text-sm font-semibold">
            <span className="flex size-7 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Info className="size-4" aria-hidden="true" />
            </span>
            Comment ça marche — protocole offline-first
          </span>
          <ChevronRight
            className="size-4 shrink-0 text-muted-foreground transition-transform duration-200 group-open:rotate-90"
            aria-hidden="true"
          />
        </summary>
        <div className="grid gap-3 px-4 pb-4 sm:px-6 md:grid-cols-3">
          {MECHANICS.map((mechanic, index) => (
            <div
              key={mechanic.title}
              className="rounded-xl border bg-muted/30 p-3"
            >
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    "flex size-7 shrink-0 items-center justify-center rounded-full",
                    index === 0
                      ? "bg-gradient-medical text-ink-medical"
                      : "bg-primary/10 text-primary",
                  )}
                  aria-hidden="true"
                >
                  <mechanic.icon className="size-4" />
                </span>
                <p className="text-sm font-medium">{mechanic.title}</p>
              </div>
              <p className="mt-1.5 text-xs leading-relaxed text-muted-foreground">
                {mechanic.text}
              </p>
            </div>
          ))}
        </div>
      </details>
    </Card>
  );
}
