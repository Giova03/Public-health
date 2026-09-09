"use client";

/**
 * Miroir local des dossiers patients (zone « mirror » d'IndexedDB, ADR-05).
 *
 * Principe d'honnêteté : le miroir ne contient QUE des dossiers réellement
 * vus en ligne (recherche, consultation). Les créations en file d'attente
 * (outbox) n'y figurent PAS — elles n'ont ni id ni phReference tant que
 * l'API ne les a pas confirmées. L'écran de synchronisation arrivera en E2.
 */

import type { Patient } from "@/lib/api/client";
import { ecrireDansMirror, listerMirror, lireDepuisMirror } from "@/lib/offline/db";

const MODULE = "patients";
const PREFIXE_CLE = "patient:";

/** Clé miroir d'un dossier — stable, dérivable depuis l'UUID. */
export function clePatient(id: string): string {
  return `${PREFIXE_CLE}${id}`;
}

/** Archiver un dossier consulté (recherche ou détail) pour lecture hors ligne. */
export async function sauvegarderPatientDansMirror(patient: Patient): Promise<void> {
  await ecrireDansMirror({
    cle: clePatient(patient.id),
    module: MODULE,
    version: patient.version,
    donnees: patient,
  });
}

/** Archiver les résultats d'une recherche (prime l'offline de demain). */
export async function sauvegarderRechercheDansMirror(
  patients: Patient[],
): Promise<void> {
  await Promise.all(patients.map(sauvegarderPatientDansMirror));
}

/** Dossier archivé localement, si l'appareil l'a déjà consulté. */
export async function lirePatientDuMirror(id: string): Promise<Patient | undefined> {
  const entree = await lireDepuisMirror<Patient>(clePatient(id));
  return entree?.module === MODULE ? entree.donnees : undefined;
}

/** Tous les dossiers du miroir (recherche locale hors ligne). */
export async function listerPatientsDuMirror(): Promise<Patient[]> {
  const entrees = await listerMirror();
  return entrees
    .filter((e) => e.module === MODULE)
    .map((e) => e.donnees as Patient)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

/**
 * Recherche locale — mêmes approximations que l'API (patronyme par préfixe,
 * prénom contenu, téléphone exact) sur les données disponibles localement.
 */
export async function chercherPatientsDuMirror(termes: {
  family?: string;
  given?: string;
  phone?: string;
}): Promise<Patient[]> {
  const patients = await listerPatientsDuMirror();
  const patronyme = normaliser(termes.family ?? "");
  const prenom = normaliser(termes.given ?? "");
  const telephone = (termes.phone ?? "").replace(/[^+0-9]/g, "");

  return patients.filter((patient) => {
    const nom = patient.names[0];
    const famille = normaliser(nom?.family ?? "");
    const prenoms = normaliser(nom?.given ?? "");
    const tel = patient.telecoms.find((t) => t.system === "phone")?.value ?? "";

    if (patronyme && !famille.startsWith(patronyme.slice(0, 3))) return false;
    if (prenom && !prenoms.includes(prenom)) return false;
    if (telephone && !tel.includes(telephone)) return false;
    return true;
  });
}

/** Même normalisation que IdentityMatcher (NFD, retrait des diacritiques). */
function normaliser(texte: string): string {
  return texte
    .trim()
    .toLocaleLowerCase("fr")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}
