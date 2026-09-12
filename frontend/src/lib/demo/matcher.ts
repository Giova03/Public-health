/**
 * PUBLIC HEALTH — moteur de rapprochement identitaire (portage fidèle du
 * domaine Java E1 `IdentityMatcher`, pour la simulation côté API).
 *
 * Règles identiques au backend :
 *  - normalisation diacritique NFD + retrait des marques non combinantes
 *    (« WÉDRAOGO Aïcha » → « wedraogo aicha ») ;
 *  - Jaro-Winkler (p = 0,1, préfixe max 4, bonus seulement si jaro >= 0,7) ;
 *  - pondérations : patronyme 0,35 · prénom 0,25 · naissance 0,20 ·
 *    téléphone 0,20 ; date approximative = année commune à 0,7 ;
 *  - verdicts : BLOCKING >= 0,90 (ou preuve matérielle : NUNP partagé,
 *    téléphone + patronyme exacts → EXACT 1,0) et REVIEW 0,55–0,90.
 */

import type {
  DuplicateCandidate,
  MatchVerdict,
  Patient,
  PatientName,
} from "@/lib/types";

export function normalize(s: string): string {
  return s
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function jaro(a: string, b: string): number {
  if (a === b) return 1;
  if (a.length === 0 || b.length === 0) return 0;

  const matchWindow = Math.max(0, Math.floor(Math.max(a.length, b.length) / 2) - 1);
  const aFlags = new Array<boolean>(a.length).fill(false);
  const bFlags = new Array<boolean>(b.length).fill(false);

  let matches = 0;
  for (let i = 0; i < a.length; i++) {
    const start = Math.max(0, i - matchWindow);
    const end = Math.min(b.length - 1, i + matchWindow);
    for (let j = start; j <= end; j++) {
      if (bFlags[j] || a[i] !== b[j]) continue;
      aFlags[i] = true;
      bFlags[j] = true;
      matches++;
      break;
    }
  }
  if (matches === 0) return 0;

  let k = 0;
  let transpositions = 0;
  for (let i = 0; i < a.length; i++) {
    if (!aFlags[i]) continue;
    while (!bFlags[k]) k++;
    if (a[i] !== b[k]) transpositions++;
    k++;
  }
  transpositions /= 2;

  return (
    (matches / a.length + matches / b.length + (matches - transpositions) / matches) / 3
  );
}

export function jaroWinkler(a: string, b: string): number {
  const j = jaro(a, b);
  if (j < 0.7) return j;
  let prefix = 0;
  const max = Math.min(4, a.length, b.length);
  while (prefix < max && a[prefix] === b[prefix]) prefix++;
  return j + prefix * 0.1 * (1 - j);
}

function birthScore(a: string, b: string): number {
  if (a === b) return 1;
  const yearA = a.slice(0, 4);
  const yearB = b.slice(0, 4);
  if (yearA && yearB && yearA === yearB) return 0.7;
  return 0;
}

function phoneScore(a: string | undefined, b: string | undefined): number {
  const na = a ? a.replace(/\D/g, "").slice(-8) : "";
  const nb = b ? b.replace(/\D/g, "").slice(-8) : "";
  if (!na || !nb) return 0;
  return na === nb ? 1 : 0;
}

export interface MatcherInput {
  family: string;
  given: string;
  birthDate: string;
  phone?: string;
  nunp?: string;
}

/** Rapprochement complet : score pondéré, verdict, preuves. */
export function matchPatient(
  input: MatcherInput,
  candidate: Patient,
): DuplicateCandidate | null {
  const reasons: string[] = [];

  // Preuves matérielles → BLOCKING direct
  const inputNunp = input.nunp;
  const candidateNunp = candidate.identifiers.find((i) => i.type === "NUNP")?.value;
  if (inputNunp && candidateNunp && inputNunp === candidateNunp) {
    return {
      patient: candidate,
      score: 1,
      verdict: "BLOCKING",
      reasons: ["NUNP identique"],
    };
  }

  const familyScore = jaroWinkler(normalize(input.family), normalize(candidate.name.family));
  const givenScore = jaroWinkler(normalize(input.given), normalize(candidate.name.given));
  const birth = birthScore(input.birthDate, candidate.birthDate);
  const phone = phoneScore(input.phone, candidate.phone);

  const score =
    familyScore * 0.35 + givenScore * 0.25 + birth * 0.2 + phone * 0.2;

  if (phone === 1 && familyScore === 1) {
    return {
      patient: candidate,
      score: 1,
      verdict: "BLOCKING",
      reasons: ["Téléphone identique et patronyme exact"],
    };
  }

  if (familyScore === 1) reasons.push("Patronyme exact");
  else if (familyScore >= 0.85) reasons.push("Patronyme très proche");
  if (givenScore === 1) reasons.push("Prénom exact");
  else if (givenScore >= 0.85) reasons.push("Prénom très proche");
  if (birth === 1) reasons.push("Date de naissance identique");
  else if (birth === 0.7) reasons.push("Année de naissance commune");
  if (phone === 1) reasons.push("Téléphone identique");

  let verdict: MatchVerdict | null = null;
  if (score >= 0.9) verdict = "BLOCKING";
  else if (score >= 0.55) verdict = "REVIEW";

  if (!verdict) return null;
  return {
    patient: candidate,
    score: Math.round(score * 100) / 100,
    verdict,
    reasons: reasons.length > 0 ? reasons : ["Score global élevé"],
  };
}

/** Recherche des candidats doublons actifs parmi les patients du serveur. */
export function findDuplicates(
  input: MatcherInput,
  patients: Patient[],
): DuplicateCandidate[] {
  return patients
    .filter((p) => p.active)
    .map((p) => matchPatient(input, p))
    .filter((c): c is DuplicateCandidate => c !== null)
    .sort((a, b) => b.score - a.score);
}

export function fullName(name: PatientName): string {
  return `${name.family} ${name.given}`;
}
