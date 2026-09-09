/**
 * Utilitaires de formatage — libellés FR Burkina, aucune donnée envoyée.
 */

/** Libellés des sexes (valeurs FHIR male/female/other/unknown). */
const LIBELLES_SEXE: Record<string, string> = {
  male: "Masculin",
  female: "Féminin",
  other: "Autre",
  unknown: "Non renseigné",
};

export function libelleSexe(gender: string): string {
  return LIBELLES_SEXE[gender] ?? gender;
}

/** « 12/03/1998 » à partir d'un ISO « 1998-03-12 » (sans fuseau, date date). */
export function formaterDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [annee, mois, jour] = iso.slice(0, 10).split("-");
  if (!annee || !mois || !jour) return iso;
  return `${jour}/${mois}/${annee}`;
}

/** Âge en années révolues — utile au comptoir d'un CSPS. */
export function calculerAge(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const naissance = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(naissance.getTime())) return null;
  const aujourdhui = new Date();
  let age = aujourdhui.getUTCFullYear() - naissance.getUTCFullYear();
  const avantAnniversaire =
    aujourdhui.getUTCMonth() < naissance.getUTCMonth() ||
    (aujourdhui.getUTCMonth() === naissance.getUTCMonth() &&
      aujourdhui.getUTCDate() < naissance.getUTCDate());
  if (avantAnniversaire) age -= 1;
  return age >= 0 && age < 130 ? age : null;
}

/** « 12/03/1998 · 27 ans » (ou « date approximative » honnête). */
export function formaterNaissance(
  iso: string | null | undefined,
  approximative: boolean,
): string {
  const date = formaterDate(iso);
  if (!iso || date === "—") return "Naissance non renseignée";
  const age = calculerAge(iso);
  const base = age !== null ? `${date} · ${age} ans` : date;
  return approximative ? `${base} (approximative)` : base;
}

/** Normalise un numéro : ne garde que les chiffres et le « + » initial. */
export function normaliserTelephone(valeur: string): string {
  const brut = valeur.replace(/[^+0-9]/g, "");
  return brut.startsWith("+") ? brut : brut.replace(/\+/g, "");
}

/**
 * Découpe un terme de recherche unique en critères miroir :
 * - chiffres → téléphone ;
 * - « OUEDRAOGO Aïcha » → patronyme + prénom ;
 * - un seul mot → patronyme.
 * (L'API cherche : patronyme par préfixe, prénom contenu, téléphone exact.)
 */
export function decouperTermeRecherche(
  terme: string,
): { family?: string; given?: string; phone?: string } {
  const net = terme.trim();
  if (!net) return {};
  if (/^[+0-9][0-9\s.-]{5,}$/.test(net)) {
    return { phone: normaliserTelephone(net) };
  }
  const parties = net.split(/\s+/);
  if (parties.length >= 2) {
    return { family: parties[0], given: parties.slice(1).join(" ") };
  }
  return { family: net };
}
