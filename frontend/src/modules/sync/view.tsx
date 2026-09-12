"use client";

/**
 * Module Synchronisation (E2) — vue « poste de commandement » offline-first.
 * Le moteur (IndexedDB 3 zones, outbox, drain idempotent, pull delta) vit
 * dans le store : cette vue le rend visible et pédagogique, sans logique
 * réseau propre.
 */

import { motion } from "framer-motion";
import type { Variants } from "framer-motion";
import { RefreshCw } from "lucide-react";
import { HowItWorksCard } from "./how-it-works";
import { OutboxCard } from "./outbox-card";
import { SyncJournalCard } from "./journal-card";
import { SyncStatusCard } from "./status-card";

const CONTAINER_VARIANTS: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.05 } },
};

const SECTION_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.22, ease: "easeOut" },
  },
};

export function SyncView() {
  return (
    <div className="space-y-4 md:space-y-6">
      <header className="hidden md:block">
        <div className="flex items-center gap-3">
          <span
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-medical text-ink-medical shadow-tile"
            aria-hidden="true"
          >
            <RefreshCw className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-xl font-semibold tracking-tight">
              Synchronisation
            </h1>
            <p className="mt-0.5 text-sm text-muted-foreground">
              Poste de commandement offline-first : état du moteur, file
              d&apos;attente et journal.
            </p>
          </div>
        </div>
      </header>

      <motion.div
        variants={CONTAINER_VARIANTS}
        initial="hidden"
        animate="visible"
        className="space-y-4 md:space-y-6"
      >
        <motion.div variants={SECTION_VARIANTS}>
          <SyncStatusCard />
        </motion.div>
        <motion.div variants={SECTION_VARIANTS}>
          <OutboxCard />
        </motion.div>
        <motion.div variants={SECTION_VARIANTS}>
          <SyncJournalCard />
        </motion.div>
        <motion.div variants={SECTION_VARIANTS}>
          <HowItWorksCard />
        </motion.div>
      </motion.div>
    </div>
  );
}
