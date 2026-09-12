"use client";

import { useMemo, useState } from "react";
import { CloudOff, ClipboardCheck, Loader2, Stethoscope } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { useToast } from "@/hooks/use-toast";
import { useAppStore } from "@/lib/store";
import { useCurrentUser } from "@/lib/session";
import type { PrescriptionItem } from "@/lib/types";
import { ConsultationStepper } from "./components/stepper";
import { ExamStep } from "./components/exam-step";
import { LinesEditor } from "./components/prescription-lines";
import { PatientStep } from "./components/patient-step";
import {
  DIAGNOSIS_CODES,
  EMPTY_EXAM,
  OTHER_DIAGNOSIS,
  type DraftLine,
  type ExamDraft,
  dosageFromForm,
  drugByDci,
  frDecimal,
  makeLine,
  resolvedDiagnosis,
  suggestedQuantity,
} from "./helpers";

/**
 * Vue Consultation (E3) — flux guidé en 3 étapes.
 *
 * 1. Patient : miroir MPI local (recherche insensible aux diacritiques),
 *    porte de sortie vers la création de dossier ;
 * 2. Examen : motif, diagnostic (référentiel national), constantes ;
 * 3. Ordonnance : lignes de médicaments (quantité suggérée), validation.
 *
 * La soumission passe par le store : en ligne → API, hors ligne → outbox
 * avec toast honnête. Rien n'est jamais perdu.
 */
export function ConsultationView() {
  const { toast } = useToast();
  const patients = useAppStore((s) => s.patients);
  const selectedPatientId = useAppStore((s) => s.selectedPatientId);
  const selectPatient = useAppStore((s) => s.selectPatient);
  const goTo = useAppStore((s) => s.goTo);
  const simulatedOnline = useAppStore((s) => s.simulatedOnline);
  const createPrescription = useAppStore((s) => s.createPrescription);
  const createConsultation = useAppStore((s) => s.createConsultation);
  const user = useCurrentUser();

  const [step, setStep] = useState(0);
  const [exam, setExam] = useState<ExamDraft>(EMPTY_EXAM);
  const [lines, setLines] = useState<DraftLine[]>([makeLine()]);
  const [submitting, setSubmitting] = useState(false);

  const patient = useMemo(
    () => patients.find((p) => p.id === selectedPatientId) ?? null,
    [patients, selectedPatientId],
  );

  const diagnosis = resolvedDiagnosis(exam);
  /** Code du référentiel (I14) — l'étiquette seule ne suffit plus. */
  const diagnosticCode = () =>
    exam.diagnosisChoice === OTHER_DIAGNOSIS
      ? "AUTRE"
      : (DIAGNOSIS_CODES[exam.diagnosisChoice] ?? "AUTRE");
  const linesValid =
    lines.length > 0 &&
    lines.every((l) => l.drug !== "" && l.quantity >= 1 && l.durationDays >= 1);
  const completed = [patient !== null, diagnosis.trim().length > 0, linesValid];

  const updateLine = (index: number, patch: Partial<DraftLine>) => {
    setLines((current) => {
      const next = current.map((line, i) => {
        if (i !== index) return line;
        const merged = { ...line, ...patch };
        // Quantité suggérée recalculée si l'utilisateur ne l'a pas figée.
        if (
          !merged.quantityTouched &&
          (patch.frequency !== undefined || patch.durationDays !== undefined)
        ) {
          merged.quantity = suggestedQuantity(
            merged.frequency,
            merged.durationDays,
          );
        }
        // Dosage prérempli depuis la forme galénique tant que non touché.
        if (patch.drug !== undefined && !merged.dosageTouched) {
          merged.dosage = dosageFromForm(drugByDci(patch.drug)?.form ?? "");
        }
        return merged;
      });
      return next;
    });
  };

  const removeLine = (index: number) => {
    setLines((current) => {
      const next = current.filter((_, i) => i !== index);
      return next.length > 0 ? next : [makeLine()];
    });
  };

  const addLine = () => setLines((current) => [...current, makeLine()]);

  const submit = async () => {
    if (!patient || !linesValid) return;
    setSubmitting(true);

    // V14 (I4) : l'ACTE CLINIQUE est PERSISTÉ EN ENTIER — motif,
    // constantes (TA, T°, poids), notes et diagnostic codé. Le registre
    // papier du CSPS ne contient plus rien que la plateforme ignore.
    const ta = exam.bloodPressure?.split(/[\s/]/).map((x) => Number(x.replace(",", ".")));
    const consultResult = await createConsultation({
      patientId: patient.id,
      motif: exam.motif.trim(),
      diagnosticCode: diagnosticCode(),
      diagnosticLabel: diagnosis.trim(),
      notes: exam.notes.trim() || undefined,
      constantes: {
        taSystolique: ta && ta.length >= 2 && !Number.isNaN(ta[0]) ? ta[0] : undefined,
        taDiastolique: ta && ta.length >= 2 && !Number.isNaN(ta[1]) ? ta[1] : undefined,
        temperatureC: exam.temperature ? Number(exam.temperature.replace(",", ".")) : undefined,
        poidsKg: exam.weight ? Number(exam.weight.replace(",", ".")) : undefined,
      },
    });
    if (consultResult.status !== "ok") {
      setSubmitting(false);
      toast({
        title: "Consultation refusée",
        description: "message" in consultResult ? consultResult.message : "Erreur",
        variant: "destructive",
      });
      return;
    }

    const items: PrescriptionItem[] = lines.map((l) => ({
      drug: l.drug,
      dosage: l.dosage || drugByDci(l.drug)?.form || "",
      frequency: l.frequency,
      durationDays: l.durationDays,
      quantity: l.quantity,
    }));
    const result = await createPrescription({
      patientId: patient.id,
      prescriber: user.fullName,
      facility: user.facility,
      diagnosis: diagnosis.trim(),
      items,
    });
    setSubmitting(false);

    if (result.status === "ok") {
      toast({
        title: "Consultation validée",
        description: `Acte clinique ET ordonnance enregistrés pour ${patient.name.family} ${patient.name.given}.`,
      });
      reset();
      goTo("prescriptions");
    } else if (result.status === "queued") {
      toast({
        title: "Consultation enregistrée hors ligne",
        description:
          "L'ordonnance partira à la synchronisation — elle est déjà visible sur ce poste.",
      });
      reset();
      goTo("prescriptions");
    } else {
      toast({
        title: "Validation impossible",
        description: result.message,
        variant: "destructive",
      });
    }
  };

  const reset = () => {
    setStep(0);
    setExam(EMPTY_EXAM);
    setLines([makeLine()]);
  };

  return (
    <div className="space-y-4">
      {!simulatedOnline && (
        <div
          role="status"
          className="flex items-center gap-2.5 rounded-xl border border-amber-500/40 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-400"
        >
          <CloudOff className="h-4 w-4 shrink-0" aria-hidden="true" />
          Hors ligne : l'ordonnance partira à la synchronisation.
        </div>
      )}

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2.5 text-base">
            <span
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-gradient-medical text-ink-medical"
              aria-hidden="true"
            >
              <Stethoscope className="h-5 w-5" />
            </span>
            Nouvelle consultation
          </CardTitle>
          <CardDescription>
            {user.facility} · {user.fullName} — les trois
            étapes restent modifiables en revenant en arrière.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <ConsultationStepper
            current={step}
            completed={completed}
            onStep={setStep}
          />

          {step === 0 && (
            <PatientStep
              patient={patient}
              patients={patients}
              onSelect={selectPatient}
              onCreate={() => goTo("patients")}
            />
          )}

          {step === 1 && (
            <ExamStep exam={exam} onChange={(patch) => setExam((e) => ({ ...e, ...patch }))} />
          )}

          {step === 2 && (
            <div className="space-y-4">
              {patient && diagnosis.trim().length > 0 && (
                <div className="rounded-xl border bg-muted/40 p-3.5 text-sm">
                  <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="font-semibold">
                      {patient.name.family} {patient.name.given}
                    </span>
                    <span className="text-muted-foreground">—</span>
                    <span className="text-primary">{diagnosis.trim()}</span>
                  </p>
                  {(exam.bloodPressure || exam.temperature || exam.weight) && (
                    <p className="mt-1 text-xs text-muted-foreground tnum">
                      {[
                        exam.bloodPressure && `TA ${exam.bloodPressure}`,
                        exam.temperature && `T° ${frDecimal(exam.temperature)} °C`,
                        exam.weight && `${frDecimal(exam.weight)} kg`,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                  )}
                </div>
              )}
              <LinesEditor
                lines={lines}
                onUpdateLine={updateLine}
                onRemoveLine={removeLine}
                onAddLine={addLine}
              />
            </div>
          )}

          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            {step > 0 && (
              <Button
                variant="outline"
                className="h-11"
                onClick={() => setStep((s) => s - 1)}
                disabled={submitting}
              >
                Retour
              </Button>
            )}
            {step < 2 ? (
              <Button
                className="h-11"
                onClick={() => setStep((s) => s + 1)}
                disabled={step === 0 ? patient === null : !completed[1]}
              >
                Continuer
              </Button>
            ) : (
              <Button
                variant="medical"
                className="h-11"
                onClick={submit}
                disabled={!patient || !linesValid || submitting}
              >
                {submitting ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                ) : (
                  <ClipboardCheck className="h-4 w-4" aria-hidden="true" />
                )}
                Valider la consultation
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
