"use client";

import { useMemo, useState } from "react";
import { useAppStore } from "@/lib/store";
import { PrescriptionDetail } from "./prescription-detail";
import { PrescriptionList } from "./prescription-list";
import { searchPrescriptions } from "./prescription-helpers";

type StatusFilter = "ALL" | "ACTIVE" | "COMPLETED";

/**
 * Vue Ordonnances (E3) — miroir local : liste filtrable ↔ fiche détaillée
 * (dispensation partielle cumulée, contre-entrées tracées).
 */
export function PrescriptionsView() {
  const prescriptions = useAppStore((s) => s.prescriptions);
  const selectedPrescriptionId = useAppStore((s) => s.selectedPrescriptionId);
  const selectPrescription = useAppStore((s) => s.selectPrescription);
  const selectPatient = useAppStore((s) => s.selectPatient);
  const goTo = useAppStore((s) => s.goTo);

  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<StatusFilter>("ALL");

  const current = useMemo(
    () => prescriptions.find((p) => p.id === selectedPrescriptionId) ?? null,
    [prescriptions, selectedPrescriptionId],
  );

  const visible = useMemo(() => {
    const searched = searchPrescriptions(prescriptions, query);
    const filtered =
      filter === "ALL"
        ? searched
        : searched.filter((p) => p.status === filter);
    return [...filtered].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }, [prescriptions, query, filter]);

  if (current) {
    return (
      <PrescriptionDetail
        prescription={current}
        onBack={() => selectPrescription(null)}
        onOpenPatient={(patientId) => {
          selectPatient(patientId);
          goTo("patients");
        }}
      />
    );
  }

  return (
    <PrescriptionList
      prescriptions={visible}
      query={query}
      onQueryChange={setQuery}
      filter={filter}
      onFilterChange={setFilter}
      onOpen={selectPrescription}
      onNewConsultation={() => goTo("consultation")}
    />
  );
}
