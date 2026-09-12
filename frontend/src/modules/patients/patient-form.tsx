"use client";

/**
 * Module Patients (E1) — formulaire de création d'un dossier MPI.
 *
 * Contrat UX E1 : à la soumission, un clientRequestId UUID v7 est généré ;
 * le résultat « conflict » (409 serveur) n'est PAS une erreur mais un
 * branchement vers le dialogue de doublons. Hors ligne : création
 * optimiste honnête (queued, référence à la synchronisation).
 *
 * Mobile-first : dialog quasi plein écran sur téléphone, zone de champs
 * défilable, actions ancrées en pied.
 */

import { useForm, useWatch, type DefaultValues } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { isValid, parseISO } from "date-fns";
import { Loader2, Save, CloudOff, IdCard } from "lucide-react";
import { z } from "zod";
import type { CreatePatientInput, DuplicateCandidate, Patient } from "@/lib/types";
import { useAppStore } from "@/lib/store";
import { useCurrentUser } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { uuidV7 } from "@/lib/uuid";
import { FACILITY_NAMES } from "@/lib/demo/reference";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { cn } from "@/lib/utils";

export type CreateStatus = "created" | "replayed" | "queued";

export interface PatientFormDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 409 : brancher vers le dialogue de doublons (le formulaire reste ouvert). */
  onConflict: (input: CreatePatientInput, candidates: DuplicateCandidate[]) => void;
  /** Succès (créé, rejeu idempotent ou file hors ligne). */
  onCreated: (patient: Patient, status: CreateStatus) => void;
}

/* ------------------------------ Validation ----------------------------- */

function isValidBirthDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = parseISO(value);
  if (!isValid(date)) return false;
  if (date.getTime() > Date.now()) return false;
  return date.getFullYear() >= 1900;
}

const patientFormSchema = z.object({
  family: z.string().trim().min(2, "Deux caractères minimum."),
  given: z.string().trim().min(2, "Deux caractères minimum."),
  gender: z.enum(["M", "F"], { message: "Le sexe est obligatoire." }),
  birthDate: z
    .string()
    .min(1, "La date de naissance est obligatoire.")
    .refine(isValidBirthDate, "Date invalide ou dans le futur (min. 1900)."),
  phone: z
    .string()
    .trim()
    .refine(
      (v) => v === "" || v.replace(/\D/g, "").length === 8,
      "Numéro burkinabè : 8 chiffres (ex. 70 12 34 56).",
    ),
  identifierType: z.enum(["NUNP", "CNIB"]),
  identifierValue: z
    .string()
    .trim()
    .refine(
      (v) => v === "" || v.length >= 4,
      "Numéro trop court (4 caractères minimum).",
    ),
  facility: z.string().min(1, "La structure de recensement est obligatoire."),
  village: z.string().trim(),
});

type PatientFormValues = z.infer<typeof patientFormSchema>;

const DEFAULT_VALUES: DefaultValues<PatientFormValues> = {
  family: "",
  given: "",
  gender: undefined,
  birthDate: "",
  phone: "",
  identifierType: "NUNP",
  identifierValue: "",
  facility: "",
  village: "",
};

/* ------------------------------ Composant ------------------------------ */

export function PatientFormDialog({
  open,
  onOpenChange,
  onConflict,
  onCreated,
}: PatientFormDialogProps) {
  const createPatient = useAppStore((s) => s.createPatient);
  const online = useAppStore((s) => s.simulatedOnline);
  const { toast } = useToast();
  const user = useCurrentUser();
  const defaultValues = { ...DEFAULT_VALUES, facility: user.facility };

  const form = useForm<PatientFormValues>({
    resolver: zodResolver(patientFormSchema),
    defaultValues,
  });
  const isSubmitting = form.formState.isSubmitting;

  const onSubmit = form.handleSubmit(async (values) => {
    const clientRequestId = uuidV7();
    const identifierValue = values.identifierValue.trim();
    const input: CreatePatientInput = {
      clientRequestId,
      name: { family: values.family.trim(), given: values.given.trim() },
      gender: values.gender,
      birthDate: values.birthDate,
      phone: values.phone.trim() || undefined,
      identifiers: identifierValue
        ? [{ type: values.identifierType, value: identifierValue }]
        : undefined,
      facility: values.facility,
      village: values.village.trim() || undefined,
    };

    const result = await createPatient(input);
    switch (result.status) {
      case "conflict":
        onConflict(input, result.candidates);
        break;
      case "created":
      case "replayed":
      case "queued":
        form.reset(defaultValues);
        onCreated(result.patient, result.status);
        break;
      case "error":
        toast({
          variant: "destructive",
          title: "Création impossible",
          description: result.message,
        });
        break;
    }
  });

  const identifierType = useWatch({ control: form.control, name: "identifierType" });
  const identifierPlaceholder =
    identifierType === "CNIB" ? "ex. B12345678" : "ex. 0000123456";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="top-[50%] grid max-h-[92dvh] w-full grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-lg">
        <DialogHeader className="space-y-2 px-5 pt-6 pb-4 text-left sm:px-6">
          <DialogTitle className="flex items-center gap-2">
            <span
              className="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-primary"
              aria-hidden="true"
            >
              <IdCard className="h-4 w-4" />
            </span>
            Nouveau dossier patient
          </DialogTitle>
          <DialogDescription>
            Identité civile minimale — un numéro unique national (NUNP) sera
            rattaché par l&apos;agent MPI du district.
          </DialogDescription>
          {!online && (
            <div className="flex items-start gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-xs text-amber-700 dark:text-amber-400">
              <CloudOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <p>
                Hors ligne&nbsp;: la vérification complète des doublons se fera
                à la synchronisation.
              </p>
            </div>
          )}
        </DialogHeader>

        <Form {...form}>
          <form
            onSubmit={onSubmit}
            className="flex min-h-0 flex-col"
            noValidate
          >
            <div className="max-h-[54dvh] space-y-4 overflow-y-auto scrollbar-thin px-5 pb-4 sm:max-h-none sm:px-6">
              {/* Nom / prénom */}
              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="family"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Nom (patronyme) *</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="family-name"
                          placeholder="ex. OUÉDRAOGO"
                          className="h-11"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="given"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Prénom *</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="given-name"
                          placeholder="ex. Aïcha"
                          className="h-11"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              {/* Sexe */}
              <FormField
                control={form.control}
                name="gender"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Sexe *</FormLabel>
                    <FormControl>
                      <RadioGroup
                        value={field.value ?? ""}
                        onValueChange={field.onChange}
                        className="grid grid-cols-2 gap-3"
                      >
                        {(
                          [
                            { value: "M", label: "Masculin" },
                            { value: "F", label: "Féminin" },
                          ] as const
                        ).map((opt) => (
                          <Label
                            key={opt.value}
                            className={cn(
                              "flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border px-4 py-3 text-sm font-medium transition-colors",
                              "hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring",
                              field.value === opt.value
                                ? "border-primary bg-primary/5 text-primary"
                                : "text-foreground",
                            )}
                          >
                            <RadioGroupItem value={opt.value} />
                            {opt.label}
                          </Label>
                        ))}
                      </RadioGroup>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Naissance / téléphone */}
              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="birthDate"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Date de naissance *</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="date"
                          className="h-11 dark:[color-scheme:dark]"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="phone"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Téléphone</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="tel"
                          inputMode="numeric"
                          autoComplete="tel"
                          placeholder="70 12 34 56"
                          className="h-11 tnum"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              {/* Identifiant national */}
              <FormField
                control={form.control}
                name="identifierValue"
                render={({ field: valueField }) => (
                  <FormItem>
                    <FormLabel>Identifiant national</FormLabel>
                    <div className="grid grid-cols-[8.5rem_minmax(0,1fr)] gap-3">
                      <FormField
                        control={form.control}
                        name="identifierType"
                        render={({ field: typeField }) => (
                          <Select
                            value={typeField.value}
                            onValueChange={typeField.onChange}
                          >
                            <SelectTrigger
                              aria-label="Type d'identifiant national"
                              className="h-11"
                            >
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="NUNP">NUNP</SelectItem>
                              <SelectItem value="CNIB">CNIB</SelectItem>
                            </SelectContent>
                          </Select>
                        )}
                      />
                      <FormControl>
                        <Input
                          {...valueField}
                          placeholder={identifierPlaceholder}
                          className="h-11"
                        />
                      </FormControl>
                    </div>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Structure */}
              <FormField
                control={form.control}
                name="facility"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Structure de recensement *</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger className="h-11">
                          <SelectValue placeholder="Choisir une structure" />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent className="max-h-64 overflow-y-auto scrollbar-thin">
                        {FACILITY_NAMES.map((name) => (
                          <SelectItem key={name} value={name}>
                            {name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormMessage />
                  </FormItem>
                )}
              />

              {/* Village */}
              <FormField
                control={form.control}
                name="village"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Village / quartier</FormLabel>
                    <FormControl>
                      <Input
                        {...field}
                        placeholder="ex. Tanghin, secteur 22"
                        className="h-11"
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>

            {/* Pied ancré : actions */}
            <div className="flex items-center justify-end gap-3 border-t bg-muted/40 px-5 py-4 sm:px-6">
              <Button
                type="button"
                variant="ghost"
                className="min-h-11"
                onClick={() => onOpenChange(false)}
              >
                Annuler
              </Button>
              <Button
                type="submit"
                className="min-h-11 px-5"
                disabled={isSubmitting}
              >
                {isSubmitting ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                    Enregistrement…
                  </>
                ) : (
                  <>
                    <Save className="h-4 w-4" aria-hidden="true" />
                    Enregistrer le dossier
                  </>
                )}
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}
