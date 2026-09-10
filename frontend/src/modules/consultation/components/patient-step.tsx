"use client";

import { useState } from "react";
import { CloudOff, Search, UserPlus, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import type { Patient } from "@/lib/types";
import { ageOf, displayName, initialsOf, searchPatients } from "../helpers";
import { cn } from "@/lib/utils";

/**
 * Étape 1 — Patient.
 * Patient déjà sélectionné → carte récapitulative (avec badge « en attente »
 * si le dossier a été créé hors ligne). Sinon mini-recherche dans le miroir
 * MPI + porte de sortie « Créer un nouveau dossier » vers le module Patients.
 */

interface PatientStepProps {
  patient: Patient | null;
  patients: Patient[];
  onSelect: (id: string) => void;
  onCreate: () => void;
}

function PendingBadge() {
  return (
    <Badge
      variant="outline"
      className="gap-1 border-amber-500/40 bg-amber-500/10 text-amber-600 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-400"
      title="Dossier créé hors ligne, en attente de synchronisation"
    >
      <CloudOff className="h-3 w-3" aria-hidden="true" />
      En attente
    </Badge>
  );
}

function PatientAvatar({
  name,
  className,
}: {
  name: Patient["name"];
  className?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary",
        className,
      )}
    >
      {initialsOf(name)}
    </span>
  );
}

export function PatientStep({
  patient,
  patients,
  onSelect,
  onCreate,
}: PatientStepProps) {
  const [query, setQuery] = useState("");
  const [changing, setChanging] = useState(false);

  const showSearch = patient === null || changing;
  const results = searchPatients(patients, query);
  const hasQuery = query.trim().length >= 2;

  return (
    <div className="space-y-4">
      {patient !== null && (
        <div className="flex items-center gap-3 rounded-xl border border-primary/25 bg-primary/5 p-4">
          <PatientAvatar name={patient.name} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="truncate font-semibold">
                {displayName(patient.name)}
              </p>
              {patient.pendingSync && <PendingBadge />}
            </div>
            <p className="mt-0.5 text-xs text-muted-foreground">
              <span className="tnum">
                {patient.phReference || "réf. à la synchronisation"}
              </span>
              {" · "}
              {patient.gender === "F" ? "Féminin" : "Masculin"}
              {ageOf(patient.birthDate) !== null && (
                <> · {ageOf(patient.birthDate)} ans</>
              )}
              {patient.village && <> · {patient.village}</>}
            </p>
          </div>
          {changing ? (
            <Button
              variant="ghost"
              className="h-11"
              onClick={() => setChanging(false)}
            >
              Annuler
            </Button>
          ) : (
            <Button
              variant="outline"
              className="h-11"
              onClick={() => setChanging(true)}
            >
              Changer
            </Button>
          )}
        </div>
      )}

      {showSearch && (
        <div className="space-y-3">
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Rechercher un dossier (nom, prénom, réf. PH…)"
              className="h-11 pl-9"
              aria-label="Recherche de dossier patient"
              autoComplete="off"
              inputMode="search"
            />
          </div>

          {hasQuery && results.length === 0 && (
            <div
              role="status"
              className="flex items-center gap-3 rounded-xl border border-dashed p-4 text-sm text-muted-foreground"
            >
              <Users className="h-5 w-5 shrink-0" aria-hidden="true" />
              <span>
                Aucun dossier actif ne correspond à «&nbsp;{query.trim()}
                &nbsp;».
              </span>
            </div>
          )}

          {results.length > 0 && (
            <ul
              className="max-h-[300px] space-y-1 overflow-y-auto scrollbar-thin"
              aria-label="Résultats de recherche de dossiers"
            >
              {results.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    onClick={() => {
                      onSelect(p.id);
                      setChanging(false);
                    }}
                    className="flex min-h-[56px] w-full items-center gap-3 rounded-xl border border-transparent px-3 py-2 text-left outline-none transition-all hover:border-primary/40 hover:bg-accent/60 hover:shadow-tile active:scale-[0.99] focus-visible:ring-[3px] focus-visible:ring-ring/50"
                  >
                    <PatientAvatar
                      name={p.name}
                      className="h-9 w-9 text-xs"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">
                        {displayName(p.name)}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        <span className="tnum">
                          {p.phReference || "réf. à la synchronisation"}
                        </span>
                        {ageOf(p.birthDate) !== null && (
                          <> · {ageOf(p.birthDate)} ans</>
                        )}
                      </span>
                    </span>
                    {p.pendingSync && <PendingBadge />}
                  </button>
                </li>
              ))}
            </ul>
          )}

          <Separator className="sm:hidden" />

          <Button
            variant="outline"
            className="h-11 w-full border-dashed"
            onClick={onCreate}
          >
            <UserPlus className="h-4 w-4" aria-hidden="true" />
            Créer un nouveau dossier
          </Button>
        </div>
      )}
    </div>
  );
}
