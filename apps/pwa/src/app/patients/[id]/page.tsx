"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import {
  Calendar,
  GitMerge,
  IdCard,
  Phone,
  RefreshCw,
  User,
  UserRound,
} from "lucide-react";
import { AppHeader } from "@/components/app-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { useEtatReseau } from "@/hooks/use-etat-reseau";
import {
  ErreurApi,
  ErreurFusion410,
  ErreurReseau,
  chargerPatient,
  type Patient,
} from "@/lib/api/client";
import { formaterDate, formaterNaissance, libelleSexe } from "@/lib/format";
import {
  lirePatientDuMirror,
  sauvegarderPatientDansMirror,
} from "@/lib/offline/patients-mirror";

type EtatDossier =
  | { phase: "chargement" }
  | { phase: "charge"; patient: Patient; source: "api" | "miroir" }
  | { phase: "fusionne"; masterId: string | null; detail: string }
  | { phase: "erreur"; message: string }
  | { phase: "introuvable" };

const LIBELLE_IDENTIFIANT: Record<string, string> = {
  NUNP: "NUNP",
  CNIB: "CNIB",
  ANCIEN_REGISTRE: "Ancien registre",
  LOCAL: "Registre local",
};

/** Écran détail — le dossier doré du patient (lecture seule en E1). */
export default function PageDetailPatient() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const id = params?.id ?? null;
  const { enLigne, enAttente } = useEtatReseau();
  const [etat, setEtat] = useState<EtatDossier>({ phase: "chargement" });
  /** Compteur de relances (bouton « Réessayer » → l'effet se rejoue). */
  const [tentative, setTentative] = useState(0);

  useEffect(() => {
    if (!id) return;
    let ignore = false;

    const demarrer = async (ident: string) => {
      setEtat({ phase: "chargement" });

      // Hors ligne : le miroir ne contient que les dossiers déjà consultés
      // sur cet appareil — jamais de promesse en l'air.
      if (typeof window !== "undefined" && !navigator.onLine) {
        const local = await lirePatientDuMirror(ident).catch(() => undefined);
        if (ignore) return;
        setEtat(
          local
            ? { phase: "charge", patient: local, source: "miroir" }
            : {
                phase: "erreur",
                message:
                  "Hors ligne : ce dossier n'a pas encore été consulté sur cet appareil. Reconnectez-vous pour le charger.",
              },
        );
        return;
      }

      try {
        const patient = await chargerPatient(ident);
        if (ignore) return;
        // Dossier consulté = disponible hors ligne à partir de maintenant.
        void sauvegarderPatientDansMirror(patient).catch(() => undefined);
        setEtat({ phase: "charge", patient, source: "api" });
      } catch (erreur) {
        if (ignore) return;
        if (erreur instanceof ErreurFusion410) {
          // 410 : le dossier a fusionné — l'agent suit le maître.
          setEtat({
            phase: "fusionne",
            masterId: erreur.masterId,
            detail: erreur.message,
          });
        } else if (erreur instanceof ErreurApi && erreur.statut === 404) {
          setEtat({ phase: "introuvable" });
        } else if (erreur instanceof ErreurReseau) {
          const local = await lirePatientDuMirror(ident).catch(() => undefined);
          if (ignore) return;
          setEtat(
            local
              ? { phase: "charge", patient: local, source: "miroir" }
              : { phase: "erreur", message: erreur.message },
          );
        } else if (erreur instanceof ErreurApi) {
          setEtat({
            phase: "erreur",
            message: `${erreur.titre} — ${erreur.message}`,
          });
        } else {
          setEtat({ phase: "erreur", message: "Chargement impossible." });
        }
      }
    };

    void demarrer(id);
    return () => {
      ignore = true;
    };
  }, [id, tentative]);

  const ouvrirMaitre = () => {
    if (etat.phase === "fusionne" && etat.masterId) {
      router.replace(`/patients/${etat.masterId}`);
    }
  };

  return (
    <div className="min-h-screen w-full">
      <AppHeader queuedOperations={enAttente} />

      <main className="mx-auto w-full max-w-2xl flex-1 space-y-5 px-4 py-6 sm:px-6 sm:py-8">
        <div>
          <Button asChild variant="ghost" size="sm" className="-ml-2 h-9">
            <Link href="/patients">Retour à la recherche</Link>
          </Button>
        </div>

        {etat.phase === "chargement" && (
          <div className="space-y-4" aria-label="Chargement du dossier">
            <div className="flex items-center gap-4">
              <Skeleton className="h-14 w-14 rounded-full" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-6 w-2/5" />
                <Skeleton className="h-4 w-1/3" />
              </div>
            </div>
            <Skeleton className="h-28 w-full rounded-lg" />
            <Skeleton className="h-28 w-full rounded-lg" />
          </div>
        )}

        {etat.phase === "fusionne" && (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <GitMerge className="h-5 w-5 text-amber-600" aria-hidden="true" />
                Dossier fusionné
              </CardTitle>
              <CardDescription>
                Ce dossier a été fusionné dans un dossier maître — les données
                médicales continuent d&apos;y être consolidées.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">{etat.detail}</p>
              {etat.masterId ? (
                <Button onClick={ouvrirMaitre} className="mt-4 h-11">
                  <GitMerge aria-hidden="true" />
                  Ouvrir le dossier maître
                </Button>
              ) : (
                <p className="mt-3 text-xs text-muted-foreground">
                  Le dossier maître n&apos;est pas indiqué — contactez le
                  responsable du registre.
                </p>
              )}
            </CardContent>
          </Card>
        )}

        {etat.phase === "erreur" && (
          <Card className="border-destructive/40">
            <CardContent className="px-4 py-6" role="alert">
              <p className="text-sm font-medium text-destructive">
                Dossier indisponible
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {etat.message}
              </p>
              <Button
                variant="outline"
                className="mt-4 h-11"
                onClick={() => setTentative((t) => t + 1)}
              >
                <RefreshCw aria-hidden="true" />
                Réessayer
              </Button>
            </CardContent>
          </Card>
        )}

        {etat.phase === "introuvable" && (
          <Card>
            <CardContent className="flex flex-col items-center gap-2 px-4 py-10 text-center">
              <UserRound
                className="h-8 w-8 text-muted-foreground"
                aria-hidden="true"
              />
              <p className="text-sm font-medium">Dossier introuvable</p>
              <p className="text-xs text-muted-foreground">
                Ce dossier n&apos;existe pas dans le registre national (ou
                l&apos;identifiant est invalide).
              </p>
            </CardContent>
          </Card>
        )}

        {etat.phase === "charge" && (
          <DossierPatient
            patient={etat.patient}
            source={etat.source}
            enLigne={enLigne}
          />
        )}
      </main>
    </div>
  );
}

// ------------------------------------------------------------------
// Affichage du dossier doré
// ------------------------------------------------------------------

function DossierPatient({
  patient,
  source,
  enLigne,
}: {
  patient: Patient;
  source: "api" | "miroir";
  enLigne: boolean;
}) {
  const nom = patient.names[0];
  const nomComplet = nom ? `${nom.family} ${nom.given}`.trim() : "Nom non renseigné";
  const initiales = (nom?.family ?? "?").slice(0, 2).toUpperCase();
  const nomUsuel = patient.names.find((n) => n.use === "usual");

  return (
    <div className="space-y-4">
      {/* En-tête identité */}
      <div className="flex items-center gap-4">
        <span
          className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-primary/10 text-lg font-semibold text-primary"
          aria-hidden="true"
        >
          {initiales}
        </span>
        <div className="min-w-0 leading-tight">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="truncate text-xl font-semibold tracking-tight sm:text-2xl">
              {nomComplet}
            </h1>
            <Badge
              variant="outline"
              role="status"
              aria-label={patient.active ? "Dossier actif" : "Dossier fusionné ou inactif"}
              className={
                patient.active
                  ? "border-emerald-300 bg-emerald-50 text-emerald-800"
                  : "border-amber-300 bg-amber-50 text-amber-800"
              }
            >
              {patient.active ? "Dossier actif" : "Fusionné / inactif"}
            </Badge>
          </div>
          <p className="mt-1 font-mono text-xs text-muted-foreground">
            {patient.phReference}
          </p>
        </div>
      </div>

      {source === "miroir" && !enLigne && (
        <Badge
          variant="outline"
          role="status"
          className="gap-1.5 border-amber-300 bg-amber-50 text-amber-800"
        >
          Données miroir locales — dernière consultation sur cet appareil
        </Badge>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <User className="h-4 w-4 text-primary" aria-hidden="true" />
            État civil
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            <ChampEtiquette etiquette="Patronyme" valeur={nom?.family ?? "—"} />
            <ChampEtiquette etiquette="Prénom" valeur={nom?.given ?? "—"} />
            <ChampEtiquette
              etiquette="Nom usuel"
              valeur={
                nomUsuel
                  ? `${nomUsuel.family} ${nomUsuel.given}`.trim()
                  : "—"
              }
            />
            <ChampEtiquette etiquette="Sexe" valeur={libelleSexe(patient.gender)} />
            <div>
              <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Calendar className="h-3.5 w-3.5" aria-hidden="true" />
                Naissance
              </dt>
              <dd className="mt-0.5 text-sm">
                {formaterNaissance(patient.birthDate, patient.birthDateApproximative)}
              </dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Phone className="h-4 w-4 text-primary" aria-hidden="true" />
            Coordonnées
          </CardTitle>
        </CardHeader>
        <CardContent>
          {patient.telecoms.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Aucune coordonnée enregistrée.
            </p>
          ) : (
            <ul className="space-y-2">
              {patient.telecoms.map((telecom, index) => (
                <li
                  key={`${telecom.system}-${telecom.value}-${index}`}
                  className="flex items-center gap-2 text-sm"
                >
                  <Phone className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                  <span className="tabular-nums">{telecom.value}</span>
                  <Badge variant="outline" className="text-[10px] font-normal">
                    {telecom.system === "phone" ? "Téléphone" : "Courriel"}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <IdCard className="h-4 w-4 text-primary" aria-hidden="true" />
            Identifiants nationaux
          </CardTitle>
        </CardHeader>
        <CardContent>
          {patient.identifiers.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Aucun identifiant national enregistré.
            </p>
          ) : (
            <ul className="space-y-2">
              {patient.identifiers.map((identifiant, index) => (
                <li
                  key={`${identifiant.system}-${identifiant.value}-${index}`}
                  className="flex flex-wrap items-center gap-2"
                >
                  <Badge variant="secondary" className="text-[10px]">
                    {LIBELLE_IDENTIFIANT[identifiant.system] ?? identifiant.system}
                  </Badge>
                  <span className="font-mono text-sm tabular-nums">
                    {identifiant.value}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Dossier</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
            <ChampEtiquette
              etiquette="Référence PH"
              valeur={patient.phReference}
              mono
            />
            <ChampEtiquette
              etiquette="Créé le"
              valeur={formaterDate(patient.createdAt.slice(0, 10))}
            />
            <ChampEtiquette
              etiquette="Version du dossier"
              valeur={String(patient.version)}
            />
            {patient.masterId && (
              <ChampEtiquette
                etiquette="Fusionné dans"
                valeur={patient.masterId}
                mono
              />
            )}
          </dl>
          <Separator className="my-4" />
          <p className="text-xs leading-relaxed text-muted-foreground">
            Toute modification d&apos;identité (fusion, rapprochement) passe par
            la file de revue — l&apos;historique des fusions est consultable
            côté back-office (journal merge_log).
          </p>
        </CardContent>
      </Card>
    </div>
  );
}

function ChampEtiquette({
  etiquette,
  valeur,
  mono = false,
}: {
  etiquette: string;
  valeur: string;
  mono?: boolean;
}) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{etiquette}</dt>
      <dd className={`mt-0.5 text-sm ${mono ? "font-mono" : ""}`}>{valeur}</dd>
    </div>
  );
}
