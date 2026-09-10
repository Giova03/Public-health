package bf.publichealth.modules.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.identity.domain.IdentityMatcher;

/**
 * Logique pure de rapprochement — verrouillée indépendamment de la base :
 * normalisation diacritique burkinabè, Jaro-Winkler, seuils BLOCKING/REVIEW.
 */
class IdentityMatcherTest {

    private IdentityMatcher.PatientTraits traits(String family, String given,
                                                  String naissance, boolean approx,
                                                  Set<String> tels, Set<String> nationaux) {
        return new IdentityMatcher.PatientTraits(family, given,
                naissance == null ? null : LocalDate.parse(naissance), approx,
                tels, nationaux);
    }

    @Test
    @DisplayName("Normalisation : minuscules, accents retirés, espaces resserrés")
    void normalisation() {
        assertThat(IdentityMatcher.normalize("  WÉDRAOGO  Aïcha "))
                .isEqualTo("wedraogo aicha");
        assertThat(IdentityMatcher.normalize("KABORÉ")).isEqualTo("kabore");
        assertThat(IdentityMatcher.normalize("")).isEmpty();
        assertThat(IdentityMatcher.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("Identifiant national partagé : EXACT bloquant, score 1.0")
    void identifiantNationalPartage() {
        var a = traits("TRAORE", "Mariam", "1992-02-20", false, Set.of(), Set.of("NUNP:X1"));
        var b = traits("TOKO", "Autre", "1980-01-01", false, Set.of(), Set.of("NUNP:X1"));
        var r = IdentityMatcher.score(a, b);
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.method()).isEqualTo("EXACT");
        assertThat(r.blocking()).isTrue();
    }

    @Test
    @DisplayName("Téléphone + patronyme exacts : EXACT bloquant même sans naissance")
    void telephoneEtPatronymeExacts() {
        var a = traits("BATIONO", "Chantal", "2001-09-30", false, Set.of("+22670000000"), Set.of());
        var b = traits("BATIONO", "Chantal", null, true, Set.of("+22670000000"), Set.of());
        var r = IdentityMatcher.score(a, b);
        assertThat(r.method()).isEqualTo("EXACT");
        assertThat(r.blocking()).isTrue();
    }

    @Test
    @DisplayName("Zone grise : OUEDRAOGO Aminata vs Aminatou, même naissance → REVIEW, pas BLOCKING")
    void zoneGrise() {
        var a = traits("OUEDRAOGO", "Aminata", "1995-04-08", false, Set.of(), Set.of());
        var b = traits("OUEDRAOGO", "Aminatou", "1995-04-08", false, Set.of(), Set.of());
        var r = IdentityMatcher.score(a, b);
        assertThat(r.method()).isEqualTo("PROBABILISTIC");
        assertThat(r.review()).isTrue();
        assertThat(r.blocking()).isFalse();
        assertThat(r.score()).isBetween(IdentityMatcher.SEUIL_REVUE, IdentityMatcher.SEUIL_BLOQUANT);
    }

    @Test
    @DisplayName("Dates approximatives : même année suffit pour la composante naissance")
    void datesApproximatives() {
        var a = traits("SAWADOGO", "Rasmane", "1979-01-01", true, Set.of("+22676000000"), Set.of());
        var b = traits("SAWADOGO", "Rasmane", "1979-11-30", true, Set.of("+22676000000"), Set.of());
        // patronyme + prénom + tel exacts → EXACT par téléphone + patronyme
        var r = IdentityMatcher.score(a, b);
        assertThat(r.blocking()).isTrue();

        var c = traits("SAWADOGO", "Rasmata", "1979-11-30", false, Set.of(), Set.of());
        var d = traits("SAWADOGO", "Rasmane", "1979-03-15", true, Set.of(), Set.of());
        var r2 = IdentityMatcher.score(c, d);
        // family 1.0 + given proche + naissance 0.7 (année commune, approximatif)
        assertThat(r2.score()).isGreaterThanOrEqualTo(IdentityMatcher.SEUIL_REVUE);
    }

    @Test
    @DisplayName("Patronymes sans rapport : aucun signal, on crée")
    void patronymesSansRapport() {
        var a = traits("COMPAORE", "Blaise", "1990-07-14", false, Set.of("+22671223344"), Set.of("NUNP:F1"));
        var b = traits("DIALLO", "Fati", "1985-03-09", false, Set.of("+22676554433"), Set.of());
        var r = IdentityMatcher.score(a, b);
        assertThat(r.blocking()).isFalse();
        assertThat(r.review()).isFalse();
    }

    @Test
    @DisplayName("Jaro-Winkler : tolère les fautes de frappe d'un caractère")
    void toleranceTypos() {
        double proche = IdentityMatcher.jaroWinkler("ouedraogo", "ouedrago");
        double loin = IdentityMatcher.jaroWinkler("ouedraogo", "sawadogo");
        assertThat(proche).isGreaterThan(0.9);
        assertThat(loin).isLessThan(0.8);
        assertThat(IdentityMatcher.jaroWinkler("identique", "identique")).isEqualTo(1.0);
    }
}
