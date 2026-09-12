"use client";

/**
 * Module Back-office (E6) — onglet Structures : cartes sanitaires avec type
 * (libellé complet du référentiel en title), région, effectif et état
 * réseau — la CHR Ouahigouya est volontairement hors ligne : réalité
 * terrain affichée honnêtement.
 */

import { motion } from "framer-motion";
import type { Variants } from "framer-motion";
import { MapPin, Users, Wifi, WifiOff } from "lucide-react";
import { BackofficeEmpty } from "./backoffice-empty";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { FACILITY_KIND_LABELS } from "@/lib/demo/reference";
import type { HealthFacility } from "@/lib/types";
import { cn } from "@/lib/utils";

const GRID_VARIANTS: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.04 } },
};

const CARD_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.16, ease: "easeOut" },
  },
};

interface FacilitiesTabProps {
  facilities: HealthFacility[];
  loading: boolean;
  offline: boolean;
  error: string | null;
}

export function FacilitiesTab({
  facilities,
  loading,
  offline,
  error,
}: FacilitiesTabProps) {
  if (facilities.length === 0) {
    return <BackofficeEmpty loading={loading} offline={offline} error={error} />;
  }

  return (
    <motion.div
      variants={GRID_VARIANTS}
      initial="hidden"
      animate="visible"
      className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3"
      aria-label="Structures de santé"
    >
      {facilities.map((facility) => (
        <motion.div key={facility.id} variants={CARD_VARIANTS}>
          <FacilityCard facility={facility} />
        </motion.div>
      ))}
    </motion.div>
  );
}

/* ------------------------------- Carte --------------------------------- */

function FacilityCard({ facility }: { facility: HealthFacility }) {
  return (
    <Card className="gap-3 py-4">
      <CardContent className="space-y-3 px-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold" title={facility.name}>
              {facility.name}
            </p>
            <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
              <MapPin className="size-3.5 shrink-0" aria-hidden="true" />
              Région {facility.region}
            </p>
          </div>
          <Badge
            variant="outline"
            className={cn(
              "shrink-0",
              facility.kind === "DRS"
                ? "border-border bg-muted/60 text-muted-foreground"
                : "border-primary/30 bg-primary/10 text-primary",
            )}
            title={FACILITY_KIND_LABELS[facility.kind]}
          >
            {facility.kind}
          </Badge>
        </div>

        <Separator />

        <div className="flex items-center justify-between gap-2 text-sm">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            <Users className="size-4 shrink-0" aria-hidden="true" />
            Effectif
          </span>
          <span className="tnum font-semibold" title={`${facility.staffCount} agents rattachés`}>
            {facility.staffCount}
          </span>
        </div>

        <div className="flex items-center justify-between gap-2">
          <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <Wifi className="size-4 shrink-0" aria-hidden="true" />
            Réseau
          </span>
          {facility.online ? (
            <Badge
              variant="outline"
              className="border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400"
            >
              Connectée
            </Badge>
          ) : (
            <Badge
              variant="outline"
              className="gap-1 border-amber-600/40 bg-amber-500/10 text-amber-600 dark:border-amber-400/40 dark:text-amber-400"
              title="Coupure signalée — les données attendront le retour du réseau"
            >
              <WifiOff aria-hidden="true" />
              Hors ligne
            </Badge>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
