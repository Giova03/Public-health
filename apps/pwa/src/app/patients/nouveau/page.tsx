"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { CalendarCheck2, CloudOff, IdCard, Save } from "lucide-react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { AppHeader } from "@/components/app-header";
import { DialogueDoublons } from "@/components/patients/dialogue-doublons";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { useToast } from "@/hooks/use-toast";
import { useEtatReseau } from "@/hooks/use-etat-reseau";
import {
  ErreurApi,
  ErreurDoublons409,
  ErreurReseau,
  creerPatient,
  type CandidatDoublon,
  type Patient,
  type RequeteCreationPatient,
} from "@/lib/api/client";
import { libelleSexe, normaliserTelephone } from "@/lib/format";
import { ajouterALOutbox, uuidV7 } from "@/lib/offline/db";
import type { OperationHorsLigne } from "@/lib/offline/db";
import { sauvegarderPatientDansMirror } from "@/lib/offline/patients-mirror";

// ------------------------------------------------------------------
// Validation — champ par champ, messages FR
// ------------------------------------------------------------------

const GENRES = ["male", "female", "other", "unknown"] as const;
type Genre = (typeof GENRES)[number];

const estDatePassee = (valeur: string): boolean => {
  const date = new Date(`${valeur}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.getTime() < Date.now();
};

const schemaFormulaire = z.object({
  patronyme: z
    .string()
    .trim()
    .min(2, "Le patronyme est requis (2 caractères minimum).")
    .max(100, "100 caractères maximum."),
  prenom: z
    .string()
    .trim()
    .min(2, "Le prénom est requis (2 caractères minimum).")
    .max(100, "100 caractères maximum."),
  genre: z
    .string()
    .refine((valeur) => GENRES.includes(valeur as Genre), {
      message: "Sélectionnez le sexe du patient.",
    }),
  dateNaissance: z
    .string()
    .min(1, "La date de naissance est requise.")
    .refine(estDatePassee, "La date de naissance doit être dans le passé."),
  naissanceApproximative: z.boolean(),
  telephone: z
    .string()
    .refine((valeur) => {
      const net = normaliserTelephone(valeur);
      return net === "" || /^[+]?[0-9]{8,15}$/.test(net);
    }, "Numéro invalide : 8 à 15 chiffres, séparateurs tolérés."),
  nunp: z
    .string()
    .trim()
    .refine(
      (valeur) => valeur === "" || (valeur.length >= 2 && valeur.length <= 64),
      "2 à 64 caractères.",
    ),
  cnib: z
    .string()
    .trim()
    .refine(
      (valeur) => valeur === "" || (valeur.length >= 2 && valeur.length <= 64),
      "2 à 64 caractères.",
    ),
});

type ValeursFormulaire = z.infer<typeof schemaFormulaire>;

const VALEURS_INITIALES: ValeursFormulaire = {
  patronyme: "",
  prenom: "",
  genre: "",
  dateNaissance: "",
  naissanceApproximative: false,
  telephone: "",
  nunp: "",
  cnib: "",
};

/**
 * Charge de la création — conforme au CreatePatientRequest de l'API E1.
 * NOTE écart volontaire : l'API E1 n'expose pas encore l'adresse (la table
 * identity.patient_address existe en base) ni de champ « reason » libre —
 * le motif de création forcée est conservé dans la charge offline (E2).
 */
function construirePayload(
  valeurs: ValeursFormulaire,
  forceCreate: boolean,
  clientRequestId: string,
  duplicateOfRejected?: string,
): RequeteCreationPatient {
  const telecoms = [];
  const telephone = normaliserTelephone(valeurs.telephone);
  if (telephone) {
    telecoms.push({ system: "phone", value: telephone, use: "mobile" });
  }

  const identifiers = [];
  if (valeurs.nunp.trim()) {
    identifiers.push({ system: "NUNP", value: valeurs.nunp.trim() });
  }
  if (valeurs.cnib.trim()) {
    identifiers.push({ system: "CNIB", value: valeurs.cnib.trim() });
  }

  return {
    clientRequestId,
    forceCreate,
    ...(duplicateOfRejected ? { duplicateOfRejected } : {}),
    // Assuré par la validation (refine sur GENRES) — le cast est sûr.
    gender: valeurs.genre as Genre,
    birthDate: valeurs.dateNaissance,
    birthDateApproximative: valeurs.naissanceApproximative,
    names: [
      {
        use: "official",
        family: valeurs.patronyme.trim(),
        given: valeurs.prenom.trim(),
      },
    ],
    telecoms,
    identifiers,
  };
}

/** Écran de création d'un dossier patient — épique E1 côté PWA. */
export default function PageNouveauPatient() {
  const router = useRouter();
  const { enLigne, enAttente, rafraichirFile } = useEtatReseau();
  const { toast } = useToast();

  const [enCours, setEnCours] = useState(false);
  const [candidats, setCandidats] = useState<CandidatDoublon[] | null>(null);
  const [erreurGlobale, setErreurGlobale] = useState<string | null>(null);
  const [miseEnFile, setMiseEnFile] = useState<OperationHorsLigne | null>(null);

  /**
   * Clé d'idempotence stable : le premier POST (409) ne crée rien, le
   * « créer quand même » REPREND la même clé — un rejeu réseau ne peut
   * jamais créer deux dossiers (clientRequestId unique côté API).
   */
  const [cleClient, setCleClient] = useState("");

  /** Fournit la clé courante, en en générant une au premier besoin. */
  const assurerCle = (): string => {
    const cle = cleClient || uuidV7();
    if (!cleClient) setCleClient(cle);
    return cle;
  };

  const form = useForm<ValeursFormulaire>({
    resolver: zodResolver(schemaFormulaire),
    defaultValues: VALEURS_INITIALES,
  });

  const reinitialiser = () => {
    setCleClient("");
    setMiseEnFile(null);
    setErreurGlobale(null);
    setCandidats(null);
    form.reset(VALEURS_INITIALES);
  };

  // ------------------------------------------------------------------
  // Mise en file offline (outbox) — le moteur de synchro arrive en E2
  // ------------------------------------------------------------------

  const mettreEnFile = async (
    valeurs: ValeursFormulaire,
    forceCreate: boolean,
    motif: string | null,
  ) => {
    const cle = assurerCle();
    const charge = {
      ...construirePayload(
        valeurs,
        forceCreate,
        cle,
        forceCreate && candidats?.[0]
          ? candidats[0].id
          : undefined,
      ),
      motif,
    };
    const operation = await ajouterALOutbox(
      "patient.cree",
      cle,
      charge,
    );
    setMiseEnFile(operation);
    rafraichirFile();
    toast({
      title: "Dossier mis en file d'attente",
      description:
        "Il sera synchronisé dès le retour du réseau — aucun faux succès, l'écran de synchronisation arrive avec l'épique E2.",
    });
  };

  // ------------------------------------------------------------------
  // Enregistrement
  // ------------------------------------------------------------------

  const allerAuDossier = (patient: Patient) => {
    void sauvegarderPatientDansMirror(patient).catch(() => undefined);
    toast({
      title: "Dossier créé",
      description: `Référence ${patient.phReference} — ${patient.names[0]?.family ?? ""} ${patient.names[0]?.given ?? ""}.`,
    });
    router.push(`/patients/${patient.id}`);
  };

  const enregistrer = async (valeurs: ValeursFormulaire) => {
    setErreurGlobale(null);
    if (!enLigne) {
      await mettreEnFile(valeurs, false, null);
      return;
    }

    setEnCours(true);
    try {
      const patient = await creerPatient(
        construirePayload(valeurs, false, assurerCle()),
      );
      allerAuDossier(patient);
    } catch (erreur) {
      if (erreur instanceof ErreurDoublons409) {
        // 409 = contrat UX (ADR-003) : on montre les candidats, rien n'est créé.
        if (erreur.candidats.length === 0) {
          setErreurGlobale(
            `${erreur.message} (aucun candidat exploitable renvoyé — contactez le support).`,
          );
        } else {
          setCandidats(erreur.candidats);
        }
      } else if (erreur instanceof ErreurReseau) {
        // Réseau tombé en plein vol : file honnête plutôt qu'un échec sec.
        await mettreEnFile(valeurs, false, null);
      } else if (erreur instanceof ErreurApi) {
        setErreurGlobale(`${erreur.titre} — ${erreur.message}`);
      } else {
        setErreurGlobale("Erreur inattendue pendant l'enregistrement.");
      }
    } finally {
      setEnCours(false);
    }
  };

  const forcerCreation = async (motif: string) => {
    const valeurs = form.getValues();
    setEnCours(true);
    try {
      const patient = await creerPatient({
        ...construirePayload(valeurs, true, assurerCle()),
        forceCreate: true,
        duplicateOfRejected: candidats?.[0]?.id,
      });
      setCandidats(null);
      toast({
        title: "Dossier créé (après vérification des doublons)",
        description: `Création forcée tracée : candidat ${candidats?.[0]?.phReference ?? ""} rejeté après vérification humaine.`,
      });
      allerAuDossier(patient);
    } catch (erreur) {
      if (erreur instanceof ErreurReseau) {
        setCandidats(null);
        await mettreEnFile(valeurs, true, motif);
      } else if (erreur instanceof ErreurDoublons409) {
        // Rare : un nouveau candidat est apparu entre-temps — on le montre.
        setCandidats(erreur.candidats);
      } else if (erreur instanceof ErreurApi) {
        setCandidats(null);
        setErreurGlobale(`${erreur.titre} — ${erreur.message}`);
      } else {
        setCandidats(null);
        setErreurGlobale("Erreur inattendue pendant la création forcée.");
      }
    } finally {
      setEnCours(false);
    }
  };

  // ------------------------------------------------------------------
  // Rendu
  // ------------------------------------------------------------------

  if (miseEnFile) {
    return (
      <div className="min-h-screen w-full">
        <AppHeader queuedOperations={enAttente} />
        <main className="mx-auto w-full max-w-xl flex-1 px-4 py-10 sm:px-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <CloudOff className="h-5 w-5 text-amber-600" aria-hidden="true" />
                Dossier mis en file d&apos;attente
              </CardTitle>
              <CardDescription>
                Hors ligne, rien n&apos;est perdu : l&apos;enregistrement est
                conservé sur cet appareil (clé d&apos;idempotence{" "}
                <span className="font-mono text-xs">
                  {miseEnFile.opId.slice(0, 8)}
                </span>
                ) et sera synchronisé dès le retour du réseau.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-2 sm:flex-row">
              <Button asChild variant="outline" className="h-11 flex-1">
                <Link href="/patients">Voir la liste des patients</Link>
              </Button>
              <Button onClick={reinitialiser} className="h-11 flex-1">
                Créer un autre dossier
              </Button>
            </CardContent>
          </Card>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen w-full">
      <AppHeader queuedOperations={enAttente} />

      <main className="mx-auto w-full max-w-2xl flex-1 space-y-5 px-4 py-6 sm:px-6 sm:py-8">
        <div>
          <div className="flex items-center gap-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2 h-9">
              <Link href="/patients">Retour</Link>
            </Button>
          </div>
          <h1 className="mt-2 text-xl font-semibold tracking-tight sm:text-2xl">
            Nouveau dossier patient
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            La création vérifie d&apos;abord le registre national : en cas de
            doublon probable, les dossiers existants vous seront présentés
            avant toute création.
          </p>
        </div>

        {!enLigne && (
          <Badge
            variant="outline"
            role="status"
            className="gap-1.5 border-amber-300 bg-amber-50 text-amber-800"
          >
            <CloudOff className="h-3.5 w-3.5" aria-hidden="true" />
            Hors ligne — le dossier sera mis en file et synchronisé plus tard
          </Badge>
        )}

        {erreurGlobale && (
          <Card className="border-destructive/40">
            <CardContent className="px-4 py-3.5 text-sm text-destructive" role="alert">
              {erreurGlobale}
            </CardContent>
          </Card>
        )}

        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(enregistrer)}
            noValidate
            className="space-y-5"
          >
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <CalendarCheck2 className="h-4 w-4 text-primary" aria-hidden="true" />
                  Identité
                </CardTitle>
                <CardDescription>
                  Patronyme et prénom tels qu&apos;ils figurent sur les pièces
                  d&apos;état civil.
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid gap-4 sm:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="patronyme"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Patronyme</FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            autoComplete="off"
                            placeholder="Ex. : Ouédraogo"
                            className="h-11 text-base sm:text-sm"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="prenom"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Prénom</FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            autoComplete="off"
                            placeholder="Ex. : Aïcha"
                            className="h-11 text-base sm:text-sm"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="genre"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Sexe</FormLabel>
                        <Select onValueChange={field.onChange} value={field.value}>
                          <FormControl>
                            <SelectTrigger className="h-11 w-full text-base sm:text-sm">
                              <SelectValue placeholder="Sélectionner…" />
                            </SelectTrigger>
                          </FormControl>
                          <SelectContent>
                            {GENRES.map((genre) => (
                              <SelectItem key={genre} value={genre} className="py-2.5">
                                {libelleSexe(genre)}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="dateNaissance"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>Date de naissance</FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            type="date"
                            inputMode="numeric"
                            autoComplete="off"
                            className="h-11 text-base sm:text-sm"
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>

                <FormField
                  control={form.control}
                  name="naissanceApproximative"
                  render={({ field }) => (
                    <FormItem className="flex flex-row items-start gap-3 rounded-lg border p-3.5">
                      <FormControl>
                        <input
                          type="checkbox"
                          checked={field.value}
                          onChange={field.onChange}
                          onBlur={field.onBlur}
                          name={field.name}
                          ref={field.ref}
                          className="mt-0.5 h-5 w-5 cursor-pointer accent-primary"
                        />
                      </FormControl>
                      <div className="space-y-0.5 leading-none">
                        <FormLabel className="cursor-pointer text-sm font-normal">
                          Date de naissance approximative
                        </FormLabel>
                        <FormDescription>
                          Cochez si seule l&apos;année (ou une estimation) est
                          connue — utile pour les naissances non déclarées.
                        </FormDescription>
                      </div>
                    </FormItem>
                  )}
                />

                <Separator />

                <FormField
                  control={form.control}
                  name="telephone"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Téléphone (recommandé)</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          type="tel"
                          inputMode="tel"
                          autoComplete="off"
                          placeholder="Ex. : 70 12 34 56"
                          className="h-11 text-base tabular-nums sm:text-sm"
                        />
                      </FormControl>
                      <FormDescription>
                        Renforce la détection de doublons et le suivi des
                        rappels.
                      </FormDescription>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <IdCard className="h-4 w-4 text-primary" aria-hidden="true" />
                  Identifiants nationaux (optionnels)
                </CardTitle>
                <CardDescription>
                  Saisis uniquement s&apos;ils sont disponibles — ils rendent le
                  rapprochement de dossiers quasi certain.
                </CardDescription>
              </CardHeader>
              <CardContent className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="nunp"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>NUNP</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="off"
                          placeholder="Numéro unique national du patient"
                          className="h-11 text-base sm:text-sm"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
                <FormField
                  control={form.control}
                  name="cnib"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>CNIB</FormLabel>
                      <FormControl>
                        <Input
                          {...field}
                          autoComplete="off"
                          placeholder="Numéro de la carte d'identité"
                          className="h-11 text-base sm:text-sm"
                        />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </CardContent>
            </Card>

            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <Button asChild variant="outline" className="h-11">
                <Link href="/patients">Annuler</Link>
              </Button>
              <Button
                type="submit"
                disabled={enCours}
                className="h-11 px-5"
              >
                <Save aria-hidden="true" />
                {enCours ? "Enregistrement…" : "Enregistrer le dossier"}
              </Button>
            </div>
          </form>
        </Form>
      </main>

      <DialogueDoublons
        ouvert={candidats !== null}
        onOuvertChange={(ouvert) => {
          if (!ouvert) setCandidats(null);
        }}
        candidats={candidats ?? []}
        enCours={enCours}
        onChoisirCandidat={(patientId) => {
          setCandidats(null);
          router.push(`/patients/${patientId}`);
        }}
        onCreerQuandMeme={(motif) => {
          void forcerCreation(motif);
        }}
      />
    </div>
  );
}
