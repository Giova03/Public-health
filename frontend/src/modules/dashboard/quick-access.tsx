"use client";

/**
 * Module Tableau de bord — tuiles d'accès rapide aux modules de soins.
 * Pastilles circulaires teintées v0.4 : bleu (MPI), vert (consultation),
 * ambre (ordonnances), teal (paiements) — comme les colonnes d'icônes
 * de la maquette de référence.
 */

import { motion } from "framer-motion";
import { ClipboardList, CreditCard, Stethoscope, Users } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ViewId } from "@/lib/types";

interface QuickTile {
  view: ViewId;
  label: string;
  description: string;
  icon: LucideIcon;
  chip: string;
}

const TILES: QuickTile[] = [
  {
    view: "patients",
    label: "Patients",
    description: "MPI : recherche, création, doublons",
    icon: Users,
    chip: "bg-primary/10 text-primary",
  },
  {
    view: "consultation",
    label: "Consultation",
    description: "Examen clinique et ordonnance",
    icon: Stethoscope,
    chip: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  },
  {
    view: "prescriptions",
    label: "Ordonnances",
    description: "Dispensation partielle, contre-entrées",
    icon: ClipboardList,
    chip: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  },
  {
    view: "payments",
    label: "Paiements",
    description: "Encaissement FedaPay, réconciliation",
    icon: CreditCard,
    chip: "bg-teal-500/10 text-teal-600 dark:text-teal-400",
  },
];

export function QuickAccess({
  onNavigate,
  allowed,
}: {
  onNavigate: (view: ViewId) => void;
  /** Périmètre du rôle connecté (facultatif : toutes les tuiles sinon). */
  allowed?: ViewId[];
}) {
  const tiles = allowed
    ? TILES.filter((tile) => allowed.includes(tile.view))
    : TILES;

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {tiles.map((tile, index) => (
        <motion.button
          key={tile.view}
          type="button"
          onClick={() => onNavigate(tile.view)}
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{
            duration: 0.22,
            ease: "easeOut",
            delay: Math.min(index * 0.05, 0.2),
          }}
          whileHover={{ y: -3 }}
          whileTap={{ scale: 0.98 }}
          className="flex min-h-[104px] flex-col items-start gap-2.5 rounded-2xl border border-border bg-card p-4 text-left transition-all hover:border-primary/40 hover:shadow-tile focus-visible:outline-2 focus-visible:outline-ring"
        >
          <span
            className={`flex h-11 w-11 items-center justify-center rounded-full ${tile.chip}`}
          >
            <tile.icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <span>
            <span className="block text-sm font-semibold text-foreground">
              {tile.label}
            </span>
            <span className="mt-0.5 block text-xs leading-snug text-muted-foreground">
              {tile.description}
            </span>
          </span>
        </motion.button>
      ))}
    </div>
  );
}
