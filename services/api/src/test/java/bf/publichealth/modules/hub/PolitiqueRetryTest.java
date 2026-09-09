package bf.publichealth.modules.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.hub.domain.PolitiqueRetry;
import bf.publichealth.modules.hub.domain.PolitiqueRetry.Verdict;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Politique de retransmission — toutes les branches : acquittement,
 * rejet définitif immédiat, échec transitoire, épuisement des
 * tentatives (DLQ).
 */
@DisplayName("PolitiqueRetry (verdicts après réponse du transport)")
class PolitiqueRetryTest {

    private static final ReponseTransport ACK = ReponseTransport.acquitte(42L, "reçu");
    private static final ReponseTransport TRANSITOIRE =
            ReponseTransport.echecTransitoire("503 du partenaire");
    private static final ReponseTransport REJET =
            ReponseTransport.rejetDefinitif("contrat invalide");

    private final PolitiqueRetry protocole = new PolitiqueRetry();   // 8 tentatives

    @Test
    @DisplayName("Acquittement → ACQUITTER, toujours (même après des échecs)")
    void acquittement() {
        assertThat(protocole.verdict(ACK, 1)).isEqualTo(Verdict.ACQUITTER);
        assertThat(protocole.verdict(ACK, 7)).isEqualTo(Verdict.ACQUITTER);
        assertThat(new PolitiqueRetry(2).verdict(ACK, 2)).isEqualTo(Verdict.ACQUITTER);
    }

    @Test
    @DisplayName("Rejet définitif → LETTER_MORTE immédiate (même à la 1re tentative)")
    void rejetDefinitif() {
        assertThat(protocole.verdict(REJET, 1)).isEqualTo(Verdict.LETTER_MORTE);
        assertThat(new PolitiqueRetry(2).verdict(REJET, 1)).isEqualTo(Verdict.LETTER_MORTE);
    }

    @Test
    @DisplayName("Échec transitoire sous la limite → RETENTER")
    void echecTransitoireRetente() {
        assertThat(protocole.verdict(TRANSITOIRE, 1)).isEqualTo(Verdict.RETENTER);
        assertThat(protocole.verdict(TRANSITOIRE, 7)).isEqualTo(Verdict.RETENTER);
    }

    @Test
    @DisplayName("Tentatives épuisées (≥ N, défaut 8) → LETTER_MORTE (DLQ)")
    void tentativesEpuisees() {
        assertThat(protocole.verdict(TRANSITOIRE, 8)).isEqualTo(Verdict.LETTER_MORTE);
        assertThat(protocole.verdict(TRANSITOIRE, 9)).isEqualTo(Verdict.LETTER_MORTE);
    }

    @Test
    @DisplayName("Limite configurable : N=2 → lettre morte dès la 2e tentative")
    void limiteConfigurable() {
        PolitiqueRetry severe = new PolitiqueRetry(2);
        assertThat(severe.verdict(TRANSITOIRE, 1)).isEqualTo(Verdict.RETENTER);
        assertThat(severe.verdict(TRANSITOIRE, 2)).isEqualTo(Verdict.LETTER_MORTE);
        assertThat(severe.tentativesMax()).isEqualTo(2);
    }

    @Test
    @DisplayName("Gardes : réponse et tentatives obligatoires, N ≥ 1")
    void gardes() {
        assertThatThrownBy(() -> protocole.verdict(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Réponse de transport manquante");
        assertThatThrownBy(() -> protocole.verdict(TRANSITOIRE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Au moins une tentative");
        assertThatThrownBy(() -> new PolitiqueRetry(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("≥ 1");
    }

    @Test
    @DisplayName("Filigrane de réponse : présent, absent — lecture propre")
    void filigraneDeReponse() {
        assertThat(ACK.filigrane().isPresent()).isTrue();
        assertThat(ACK.filigrane().getAsLong()).isEqualTo(42L);
        assertThat(TRANSITOIRE.filigrane().isEmpty()).isTrue();
    }
}
