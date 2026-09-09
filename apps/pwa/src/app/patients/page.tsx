"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import {
  CloudOff,
  Search,
  TriangleAlert,
  UserPlus,
  UserSearch,
} from "lucide-react";
import { CarteResultat } from "@/components/patients/carte-resultat";
import { AppHeader } from "@/components/app-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { useEtatReseau } from "@/hooks/use-etat-reseau";
import {
  ErreurApi,
  ErreurReseau,
  rechercherPatients,
  type Patient,
} from "@/lib/api/client";
import { decouperTermeRecherche } from "@/lib/format";
import {
  chercherPatientsDuMirror,
  listerPatientsDuMirror,
  sauvegarderRechercheDansMirror,
} from "@/lib/offline/patients-mirror";

type EtatRecherche =
  | { phase: "repos" }
  | { phase: "chargement" }
  | {
      phase: "resultats";
      resultats: Patient[];
      source: "api" | "miroir";
      note?: string;
    }
  | { phase: "vide"; source: "api" | "miroir"; note?: string }
  | { phase: "erreur"; message: string };

/** Écran liste/recherche des patients — MPI national (épique E1, côté PWA). */
export default function PagePatients() {
  const { enLigne, enAttente } = useEtatReseau();
  const { toast } = useToast();
  const [terme, setTerme] = useState("");
  const [etat, setEtat] = useState<EtatRecherche>({ phase: "repos" });
  const [miroirDisponible, setMiroirDisponible] = useState(false);
  const dernierTerme = useRef("");

  useEffect(() => {
    // Le badge « données miroir » n'apparaît que si l'appareil a VRAIMENT
    // des dossiers locaux — jamais de promesse en l'air.
    listerPatientsDuMirror()
      .then((patients) => setMiroirDisponible(patients.length > 0))
      .catch(() => setMiroirDisponible(false));
  }, []);

  const lancerRecherche = useCallback(
    async (brut: string) => {
      const net = brut.trim();
      dernierTerme.current = net;
      if (!net) {
        setEtat({ phase: "repos" });
        return;
      }
      const criteres = decouperTermeRecherche(net);
      setEtat({ phase: "chargement" });

      if (!enLigne) {
        const locaux = await chercherPatientsDuMirror(criteres).catch(
          () => [] as Patient[],
        );
        if (dernierTerme.current !== net) return;
        setEtat(
          locaux.length > 0
            ? {
                phase: "resultats",
                resultats: locaux,
                source: "miroir",
                note: "Recherche locale sur les dossiers déjà consultés sur cet appareil.",
              }
            : {
                phase: "vide",
                source: "miroir",
                note: "Hors ligne : seuls les dossiers déjà consultés sur cet appareil sont recherchables.",
              },
        );
        return;
      }

      try {
        const resultats = await rechercherPatients(criteres);
        if (dernierTerme.current !== net) return;
        // Prime l'offline de demain : chaque recherche alimente le miroir.
        void sauvegarderRechercheDansMirror(resultats).catch(() => undefined);
        setMiroirDisponible((deja) => deja || resultats.length > 0);
        setEtat(
          resultats.length > 0
            ? { phase: "resultats", resultats, source: "api" }
            : { phase: "vide", source: "api" },
        );
      } catch (erreur) {
        if (dernierTerme.current !== net) return;
        if (erreur instanceof ErreurReseau) {
          // Le réseau a lâché : repli honnête sur le miroir, jamais un faux vide.
          const locaux = await chercherPatientsDuMirror(criteres).catch(
            () => [] as Patient[],
          );
          if (dernierTerme.current !== net) return;
          setEtat(
            locaux.length > 0
              ? {
                  phase: "resultats",
                  resultats: locaux,
                  source: "miroir",
                  note: "API injoignable — résultats locaux (dossiers déjà consultés).",
                }
              : { phase: "erreur", message: erreur.message },
          );
          return;
        }
        const api =
          erreur instanceof ErreurApi
            ? erreur
            : new ErreurApi(0, "Erreur inattendue", "Recherche impossible.");
        setEtat({ phase: "erreur", message: `${api.titre} — ${api.message}` });
      }
    },
    [enLigne],
  );

  const soumettre = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void lancerRecherche(terme);
  };

  const etatHorsLigne = !enLigne;

  return (
    <div className="min-h-screen w-full">
      <AppHeader queuedOperations={enAttente} />

      <main className="mx-auto w-full max-w-3xl flex-1 space-y-5 px-4 py-6 sm:px-6 sm:py-8">
        {/* Titre + action principale */}
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold tracking-tight sm:text-2xl">
              Patients
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              Recherche dans le registre national — mêmes critères que la
              détection de doublons à la création.
            </p>
          </div>
          <Button asChild className="h-11 px-4">
            <Link href="/patients/nouveau">
              <UserPlus aria-hidden="true" />
              Nouveau dossier
            </Link>
          </Button>
        </div>

        {/* Barre de recherche */}
        <form
          role="search"
          onSubmit={soumettre}
          className="flex flex-col gap-2 sm:flex-row"
        >
          <div className="relative flex-1">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <label htmlFor="recherche-patient" className="sr-only">
              Rechercher un patient par nom, prénom ou téléphone
            </label>
            <Input
              id="recherche-patient"
              type="search"
              inputMode="search"
              autoComplete="off"
              value={terme}
              onChange={(event) => setTerme(event.target.value)}
              placeholder="Nom, prénom ou téléphone…"
              aria-describedby="aide-recherche"
              className="h-11 pl-9 text-base sm:text-sm"
            />
          </div>
          <Button
            type="submit"
            disabled={etat.phase === "chargement"}
            className="h-11 px-5"
          >
            {etat.phase === "chargement" ? "Recherche…" : "Rechercher"}
          </Button>
        </form>
        <p id="aide-recherche" className="text-xs text-muted-foreground">
          Ex. : « Ouédraogo Aïcha », « Kaboré » ou « 70 12 34 56 ».
        </p>

        {/* Badge offline honnête */}
        {etatHorsLigne && (
          <Badge
            variant="outline"
            role="status"
            aria-label={
              miroirDisponible
                ? "Hors ligne, données miroir locales disponibles"
                : "Hors ligne, aucune donnée locale"
            }
            className="gap-1.5 border-amber-300 bg-amber-50 text-amber-800"
          >
            <CloudOff className="h-3.5 w-3.5" aria-hidden="true" />
            {miroirDisponible
              ? "Hors ligne · données miroir locales"
              : "Hors ligne · aucune donnée locale"}
          </Badge>
        )}

        {/* Région de résultats */}
        <div aria-live="polite" className="space-y-3">
          {etat.phase === "repos" && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <UserSearch
                  className="h-8 w-8 text-muted-foreground"
                  aria-hidden="true"
                />
                <p className="text-sm font-medium">Rechercher un dossier</p>
                <p className="max-w-sm text-xs leading-relaxed text-muted-foreground">
                  Saisissez un patronyme (éventuellement suivi du prénom) ou un
                  numéro de téléphone. Les dossiers consultés restent
                  disponibles hors ligne.
                </p>
              </CardContent>
            </Card>
          )}

          {etat.phase === "chargement" && (
            <div className="space-y-3" aria-label="Recherche en cours">
              {[0, 1, 2].map((i) => (
                <Card key={i} className="py-0">
                  <div className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="h-11 w-11 shrink-0 rounded-full" />
                    <div className="flex-1 space-y-2">
                      <Skeleton className="h-4 w-2/5" />
                      <Skeleton className="h-3 w-3/5" />
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          )}

          {etat.phase === "resultats" && (
            <>
              <p className="text-xs text-muted-foreground" role="status">
                {etat.resultats.length} dossier
                {etat.resultats.length > 1 ? "s" : ""} trouvé
                {etat.resultats.length > 1 ? "s" : ""}
                {etat.source === "miroir" ? " (localement)" : ""}.
                {etat.note ? ` ${etat.note}` : ""}
              </p>
              <ul className="space-y-3">
                {etat.resultats.map((patient) => (
                  <li key={patient.id}>
                    <CarteResultat patient={patient} />
                  </li>
                ))}
              </ul>
            </>
          )}

          {etat.phase === "vide" && (
            <Card>
              <CardContent className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <UserSearch
                  className="h-8 w-8 text-muted-foreground"
                  aria-hidden="true"
                />
                <p className="text-sm font-medium">Aucun dossier trouvé</p>
                <p className="max-w-sm text-xs leading-relaxed text-muted-foreground">
                  {etat.note ??
                    "Aucun dossier ne correspond à cette recherche dans le registre national."}
                </p>
                <Button asChild variant="outline" className="mt-2 h-11 px-4">
                  <Link href="/patients/nouveau">
                    <UserPlus aria-hidden="true" />
                    Créer un nouveau dossier
                  </Link>
                </Button>
              </CardContent>
            </Card>
          )}

          {etat.phase === "erreur" && (
            <Card className="border-destructive/40">
              <CardContent className="flex items-start gap-3 px-4 py-6">
                <TriangleAlert
                  className="mt-0.5 h-5 w-5 shrink-0 text-destructive"
                  aria-hidden="true"
                />
                <div className="space-y-1">
                  <p className="text-sm font-medium text-destructive">
                    Recherche impossible
                  </p>
                  <p className="text-xs leading-relaxed text-muted-foreground">
                    {etat.message}
                  </p>
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-2"
                    onClick={() => {
                      toast({
                        title: "Nouvelle tentative",
                        description:
                          "La synchronisation complète de la file arrive avec l'épique E2.",
                      });
                      void lancerRecherche(dernierTerme.current);
                    }}
                  >
                    Réessayer
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </main>
    </div>
  );
}
