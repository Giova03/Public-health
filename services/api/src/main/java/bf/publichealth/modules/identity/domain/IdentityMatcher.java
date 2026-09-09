package bf.publichealth.modules.identity.domain;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Set;

/**
 * Rapprochement d'identité — cœur métier de l'épique E1.
 *
 * <p>Pur, déterministe, sans dépendance : la même paire de dossiers donne
 * toujours le même score (auditable en rejouant l'algorithme). Le contexte
 * burkinabè impose la tolérance aux variantes orthographiques des noms
 * (OUEDRAOGO / WÉDRAOGO / OUEDRAGO) et aux dates de naissance approximatives
 * — d'où un Jaro-Winkler sur noms normalisés plutôt qu'une égalité bête.</p>
 *
 * <p>Deux verdicts au-dessus du seuil de revue :
 * BLOCKING (score ≥ 0,90, ou preuve matérielle : identifiant national ou
 * téléphone + patronyme exacts) et REVIEW (0,55–0,90, zone grise humaine).
 * En dessous de 0,55 : rien à signaler, on crée.</p>
 */
public final class IdentityMatcher {

    /** Au-dessus : la création est bloquée, l'agent voit les candidats (409). */
    public static final double SEUIL_BLOQUANT = 0.90;
    /** Entre REVUE et BLOQUANT : zone grise — file de revue humaine. */
    public static final double SEUIL_REVUE = 0.55;

    private static final double POIDS_PATRONYME = 0.35;
    private static final double POIDS_PRENOM = 0.25;
    private static final double POIDS_NAISSANCE = 0.20;
    private static final double POIDS_TELEPHONE = 0.20;

    private IdentityMatcher() {
    }

    /** Traits d'un patient, extraits et normalisés AVANT comparaison. */
    public record PatientTraits(String family, String given, LocalDate birthDate,
                                 boolean birthDateApproximative, Set<String> phones,
                                 Set<String> nationalIds) {
    }

    /** Verdict du rapprochement : le score seul ne décide rien. */
    public record MatchResult(double score, String method, boolean blocking, boolean review) {
    }

    public static MatchResult score(PatientTraits nouveau, PatientTraits existant) {
        // Preuve matérielle n°1 : identifiant national partagé (NUNP, CNIB).
        boolean identifiantPartage = nouveau.nationalIds().stream()
                .anyMatch(existant.nationalIds()::contains);
        boolean patronymeExact = normalize(nouveau.family()).equals(normalize(existant.family()))
                && normalize(nouveau.given()).equals(normalize(existant.given()));
        boolean telephonePartage = nouveau.phones().stream()
                .anyMatch(existant.phones()::contains);

        if (identifiantPartage || (patronymeExact && telephonePartage)) {
            return new MatchResult(1.0, "EXACT", true, false);
        }

        double simPatronyme = jaroWinkler(normalize(nouveau.family()), normalize(existant.family()));
        double simPrenom = jaroWinkler(normalize(nouveau.given()), normalize(existant.given()));
        double simNaissance = scoreNaissance(nouveau, existant);
        double simTelephone = telephonePartage ? 1.0 : 0.0;

        double score = POIDS_PATRONYME * simPatronyme
                + POIDS_PRENOM * simPrenom
                + POIDS_NAISSANCE * simNaissance
                + POIDS_TELEPHONE * simTelephone;

        boolean blocking = score >= SEUIL_BLOQUANT;
        boolean review = !blocking && score >= SEUIL_REVUE;
        return new MatchResult(score, "PROBABILISTIC", blocking, review);
    }

    private static double scoreNaissance(PatientTraits a, PatientTraits b) {
        if (a.birthDate() == null || b.birthDate() == null) {
            return 0.0;
        }
        if (a.birthDate().equals(b.birthDate())) {
            return 1.0;
        }
        // Date approximative déclarée d'un côté : l'année suffit (contexte rural).
        if (a.birthDateApproximative() || b.birthDateApproximative()) {
            return a.birthDate().getYear() == b.birthDate().getYear() ? 0.7 : 0.0;
        }
        return 0.0;
    }

    /**
     * Normalisation burkinabè : minuscules, accents retirés, espaces resserrés.
     * « WÉDRAOGO  Aïcha » → « wedraogo aicha ».
     */
    public static String normalize(String brut) {
        if (brut == null || brut.isBlank()) {
            return "";
        }
        String decompose = Normalizer.normalize(brut.strip().toLowerCase(), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decompose.length());
        for (int i = 0; i < decompose.length(); i++) {
            if (Character.getType(decompose.charAt(i)) != Character.NON_SPACING_MARK) {
                sb.append(decompose.charAt(i));
            }
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    /** Jaro-Winkler standard (p = 0,1, préfixe max 4) — tolérance aux typos. */
    public static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        if (jaro < 0.7) {
            return jaro; // le bonus de préfixe ne s'applique qu'aux paires déjà proches
        }
        int prefix = 0;
        int max = Math.min(4, Math.min(a.length(), b.length()));
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    private static double jaro(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int matchDistance = Math.max(Math.max(len1, len2) / 2 - 1, 0);
        boolean[] marques1 = new boolean[len1];
        boolean[] marques2 = new boolean[len2];
        int correspondances = 0;
        for (int i = 0; i < len1; i++) {
            int debut = Math.max(0, i - matchDistance);
            int fin = Math.min(i + matchDistance + 1, len2);
            for (int j = debut; j < fin; j++) {
                if (!marques2[j] && s1.charAt(i) == s2.charAt(j)) {
                    marques1[i] = true;
                    marques2[j] = true;
                    correspondances++;
                    break;
                }
            }
        }
        if (correspondances == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (marques1[i]) {
                while (!marques2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = correspondances;
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
    }
}
