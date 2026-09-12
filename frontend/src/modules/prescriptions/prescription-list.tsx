"use client";

import { motion } from "framer-motion";
import { CheckCircle2, ClipboardList, Search, User } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { Prescription } from "@/lib/types";
import { formatDate } from "@/lib/types";
import { cn } from "@/lib/utils";
import {
  STATUS_BADGE_CLASS,
  STATUS_LABELS,
  advancement,
} from "./prescription-helpers";

/**
 * Liste des ordonnances du miroir local : recherche, filtre par statut,
 * cartes avec avancement de la dispensation cumulée.
 */

type StatusFilter = "ALL" | "ACTIVE" | "COMPLETED";

interface PrescriptionListProps {
  prescriptions: Prescription[];
  query: string;
  onQueryChange: (q: string) => void;
  filter: StatusFilter;
  onFilterChange: (f: StatusFilter) => void;
  onOpen: (id: string) => void;
  onNewConsultation: () => void;
}

export function PrescriptionList({
  prescriptions,
  query,
  onQueryChange,
  filter,
  onFilterChange,
  onOpen,
  onNewConsultation,
}: PrescriptionListProps) {
  const counts = {
    ALL: prescriptions.length,
    ACTIVE: prescriptions.filter((p) => p.status === "ACTIVE").length,
    COMPLETED: prescriptions.filter((p) => p.status === "COMPLETED").length,
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search
            className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder="Patient, diagnostic, prescripteur…"
            className="h-11 pl-9"
            aria-label="Recherche d'ordonnances"
            autoComplete="off"
            inputMode="search"
          />
        </div>
        <Button variant="medical" className="h-11" onClick={onNewConsultation}>
          <ClipboardList className="h-4 w-4" aria-hidden="true" />
          Nouvelle consultation
        </Button>
      </div>

      <Tabs
        value={filter}
        onValueChange={(v) => onFilterChange(v as StatusFilter)}
      >
        <TabsList className="h-11 w-full justify-start overflow-x-auto sm:w-auto">
          <TabsTrigger value="ALL" className="h-9 gap-1.5">
            Toutes
            <span className="tnum text-xs text-muted-foreground">
              {counts.ALL}
            </span>
          </TabsTrigger>
          <TabsTrigger value="ACTIVE" className="h-9 gap-1.5">
            En cours
            <span className="tnum text-xs text-muted-foreground">
              {counts.ACTIVE}
            </span>
          </TabsTrigger>
          <TabsTrigger value="COMPLETED" className="h-9 gap-1.5">
            Soldées
            <span className="tnum text-xs text-muted-foreground">
              {counts.COMPLETED}
            </span>
          </TabsTrigger>
        </TabsList>
      </Tabs>

      <p className="text-xs text-muted-foreground" role="status">
        {prescriptions.length} ordonnance(s) dans le miroir local
      </p>

      {prescriptions.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed p-8 text-center">
          <ClipboardList className="h-8 w-8 text-muted-foreground/60" aria-hidden="true" />
          <p className="text-sm text-muted-foreground">
            Aucune ordonnance ne correspond.
            <br />
            Les consultations validées apparaissent ici, même hors ligne.
          </p>
          <Button variant="outline" className="h-11" onClick={onNewConsultation}>
            Démarrer une consultation
          </Button>
        </div>
      ) : (
        <ul className="space-y-3">
          {prescriptions.map((p, index) => {
            const adv = advancement(p);
            const patientLabel = p.patientName
              ? `${p.patientName.family} ${p.patientName.given}`
              : "Patient inconnu";
            return (
              <motion.li
                key={p.id}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.15, delay: Math.min(index * 0.04, 0.2) }}
              >
                <button
                  type="button"
                  className="w-full text-left outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 rounded-xl"
                  onClick={() => onOpen(p.id)}
                  aria-label={`Ouvrir l'ordonnance de ${patientLabel}`}
                >
                  <Card className="transition-all hover:border-primary/40 hover:shadow-tile active:scale-[0.99]">
                    <CardContent className="space-y-3 p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-2.5">
                          <span
                            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
                            aria-hidden="true"
                          >
                            <User className="h-4 w-4" />
                          </span>
                          <div className="min-w-0">
                            <p className="truncate font-semibold">
                              {patientLabel}
                            </p>
                            <p className="truncate text-xs text-muted-foreground">
                              {p.diagnosis} · {formatDate(p.date)}
                            </p>
                          </div>
                        </div>
                        <div className="flex shrink-0 flex-col items-end gap-1.5">
                          <Badge
                            variant="outline"
                            className={cn(STATUS_BADGE_CLASS[p.status])}
                          >
                            {p.status === "COMPLETED" && (
                              <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                            )}
                            {STATUS_LABELS[p.status]}
                          </Badge>
                          {p.pendingSync && (
                            <Badge
                              variant="outline"
                              className="gap-1 border-amber-500/40 bg-amber-500/10 text-[10px] text-amber-600 dark:text-amber-400"
                            >
                              En attente
                            </Badge>
                          )}
                        </div>
                      </div>

                      <div className="space-y-1.5">
                        <div className="flex items-center justify-between text-xs text-muted-foreground">
                          <span>
                            {p.items.length} médicament
                            {p.items.length > 1 ? "s" : ""} · {p.dispenses.length}{" "}
                            dispensation{p.dispenses.length > 1 ? "s" : ""}
                          </span>
                          <span className="tnum">
                            {adv.done}/{adv.total} dispensés
                          </span>
                        </div>
                        <Progress
                          value={Math.round(adv.ratio * 100)}
                          aria-label={`Avancement de la dispensation : ${Math.round(adv.ratio * 100)} %`}
                        />
                      </div>
                    </CardContent>
                  </Card>
                </button>
              </motion.li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
