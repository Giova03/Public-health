package bf.publichealth.modules.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.hub.domain.BackoffExponentiel;

/**
 * Backoff exponentiel — croissance ×2^(n-1), gigue bornée 0-50 %,
 * plafond 1 h sur le délai TOTAL. Pur : l'aléatoire est injecté.
 */
@DisplayName("Backoff exponentiel (trempe et gigue du protocole HUB)")
class BackoffExponentielTest {

    private static final BackoffExponentiel PROTOCOLE =
            new BackoffExponentiel(BackoffExponentiel.BASE_DEFAUT,
                    BackoffExponentiel.PLAFOND_DEFAUT,
                    BackoffExponentiel.JITTER_MAX_DEFAUT, () -> 0.0);

    private static final BackoffExponentiel GIGUE_MAX =
            new BackoffExponentiel(BackoffExponentiel.BASE_DEFAUT,
                    BackoffExponentiel.PLAFOND_DEFAUT,
                    BackoffExponentiel.JITTER_MAX_DEFAUT, () -> 1.0);

    @Test
    @DisplayName("Croissance : base 30 s ×2^(n-1), sans gigue")
    void croissance() {
        assertThat(PROTOCOLE.prochainDelai(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PROTOCOLE.prochainDelai(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(PROTOCOLE.prochainDelai(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(PROTOCOLE.prochainDelai(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(PROTOCOLE.prochainDelai(7)).isEqualTo(Duration.ofSeconds(1920));
    }

    @Test
    @DisplayName("Plafond : la trempe ne dépasse JAMAIS 1 h, même écrasée")
    void plafond() {
        assertThat(PROTOCOLE.prochainDelai(8)).isEqualTo(Duration.ofHours(1));  // 64 min → 1 h
        assertThat(PROTOCOLE.prochainDelai(40)).isEqualTo(Duration.ofHours(1));
        assertThat(GIGUE_MAX.prochainDelai(8)).isEqualTo(Duration.ofHours(1)); // gigue plafonnée
    }

    @Test
    @DisplayName("Gigue bornée 0-50 % du montant : [trempe, 1,5 × trempe]")
    void gigueBornee() {
        // alea = 0 → pas de gigue ; alea = 1 → +50 %.
        assertThat(GIGUE_MAX.prochainDelai(1)).isEqualTo(Duration.ofSeconds(45));
        assertThat(GIGUE_MAX.prochainDelai(2)).isEqualTo(Duration.ofSeconds(90));
        // Entre les deux bornes, n'importe quelle source rend un délai inclus.
        BackoffExponentiel moitie = new BackoffExponentiel(
                BackoffExponentiel.BASE_DEFAUT, BackoffExponentiel.PLAFOND_DEFAUT,
                BackoffExponentiel.JITTER_MAX_DEFAUT, () -> 0.5);
        // 30 s + 30 × 0,5 × 50 % = 37,5 s : strictement entre 30 et 45.
        assertThat(moitie.prochainDelai(1)).isEqualTo(Duration.ofMillis(37_500));
        assertThat(moitie.prochainDelai(2)).isEqualTo(Duration.ofMillis(75_000));
    }

    @Test
    @DisplayName("Le plafond borne le délai TOTAL : la gigue ne le franchit pas")
    void plafondBorneLeTotal() {
        BackoffExponentiel etroit = new BackoffExponentiel(
                Duration.ofSeconds(10), Duration.ofSeconds(11),
                BackoffExponentiel.JITTER_MAX_DEFAUT, () -> 1.0);
        // 10 s + 50 % = 15 s, mais le plafond 11 s gagne.
        assertThat(etroit.prochainDelai(1)).isEqualTo(Duration.ofSeconds(11));
    }

    @Test
    @DisplayName("Défauts du protocole : base 30 s, plafond 1 h")
    void defautsDuProtocole() {
        BackoffExponentiel defaut = new BackoffExponentiel();
        assertThat(defaut.base()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaut.plafond()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("Gardes : au moins un échec, jitter dans [0,1], plafond ≥ base")
    void gardes() {
        assertThatThrownBy(() -> PROTOCOLE.prochainDelai(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins un échec");
        assertThatThrownBy(() -> new BackoffExponentiel(Duration.ofSeconds(30),
                Duration.ofHours(1), 1.5, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[0, 1]");
        assertThatThrownBy(() -> new BackoffExponentiel(Duration.ofSeconds(30),
                Duration.ofSeconds(10), 0.5, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plafond");
        assertThatThrownBy(() -> new BackoffExponentiel(Duration.ofSeconds(-1),
                Duration.ofHours(1), 0.5, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
