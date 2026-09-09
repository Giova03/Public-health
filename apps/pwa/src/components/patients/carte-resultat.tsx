"use client";

import Link from "next/link";
import { Calendar, ChevronRight, Phone, User } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import type { Patient } from "@/lib/api/client";
import { formaterNaissance } from "@/lib/format";

interface CarteResultatProps {
  patient: Patient;
}

/**
 * Carte de résultat de recherche — cible tactile confortable (≥ 48 px),
 * identité lisible d'un coup d'œil : référence PH, nom, naissance, téléphone.
 */
export function CarteResultat({ patient }: CarteResultatProps) {
  const nom = patient.names[0];
  const telephone = patient.telecoms.find((t) => t.system === "phone");
  const nomComplet = nom
    ? `${nom.family} ${nom.given}`.trim()
    : "Nom non renseigné";

  return (
    <Link
      href={`/patients/${patient.id}`}
      aria-label={`Ouvrir le dossier ${patient.phReference} — ${nomComplet}`}
      className="block focus-visible:outline-none"
    >
      <Card className="py-0 transition-colors hover:border-primary/40 hover:bg-accent/40 active:bg-accent/60">
        <div className="flex items-center gap-3 px-4 py-3.5 sm:gap-4">
          <span
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
            aria-hidden="true"
          >
            <User className="h-5 w-5" />
          </span>

          <div className="min-w-0 flex-1 leading-tight">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <p className="truncate text-sm font-semibold sm:text-base">
                {nomComplet}
              </p>
              <Badge
                variant="outline"
                className="font-mono text-[10px] font-normal tracking-wide text-muted-foreground"
              >
                {patient.phReference}
              </Badge>
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="inline-flex items-center gap-1">
                <Calendar className="h-3.5 w-3.5" aria-hidden="true" />
                {formaterNaissance(
                  patient.birthDate,
                  patient.birthDateApproximative,
                )}
              </span>
              {telephone && (
                <span className="inline-flex items-center gap-1">
                  <Phone className="h-3.5 w-3.5" aria-hidden="true" />
                  <span className="tabular-nums">{telephone.value}</span>
                </span>
              )}
            </div>
          </div>

          <ChevronRight
            className="h-5 w-5 shrink-0 text-muted-foreground"
            aria-hidden="true"
          />
        </div>
      </Card>
    </Link>
  );
}
