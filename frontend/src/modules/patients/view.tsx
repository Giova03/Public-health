"use client";

/**
 * Module Patients (E1 — Identité & MPI) — point d'entrée.
 *
 * Aiguillage mono-page : `selectedPatientId` null → vue liste (recherche
 * miroir + création), sinon fiche patient. La navigation ne change jamais
 * d'URL — l'utilisateur ne voit que « / » (contrat PWA offline-first).
 */

import { Users } from "lucide-react";
import { motion } from "framer-motion";
import { useAppStore } from "@/lib/store";
import { PatientsList } from "./patient-list";
import { PatientDetail } from "./patient-detail";

export function PatientsView() {
  const selectedPatientId = useAppStore((s) => s.selectedPatientId);

  return (
    <div className="space-y-4">
      {/* Titre de vue (le shell affiche déjà le libellé sur mobile) */}
      <header className="hidden items-center gap-3 md:flex">
        <span
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-medical text-ink-medical"
          aria-hidden="true"
        >
          <Users className="h-5 w-5" />
        </span>
        <div className="flex flex-col gap-1">
          <h2 className="text-xl font-semibold tracking-tight">
            Dossiers patients
          </h2>
          <p className="text-sm text-muted-foreground">
            Registre national (MPI) — recherche miroir instantanée, enrichie
            par le serveur lorsque le réseau est disponible.
          </p>
        </div>
      </header>

      <motion.div
        key={selectedPatientId ?? "liste"}
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.18, ease: "easeOut" }}
      >
        {selectedPatientId ? (
          <PatientDetail patientId={selectedPatientId} />
        ) : (
          <PatientsList />
        )}
      </motion.div>
    </div>
  );
}
