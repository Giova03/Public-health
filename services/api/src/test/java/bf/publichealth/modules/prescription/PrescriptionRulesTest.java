package bf.publichealth.modules.prescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.prescription.domain.DispensingRules;
import bf.publichealth.modules.prescription.domain.IllegalPrescriptionTransitionException;
import bf.publichealth.modules.prescription.domain.PrescriptionInactiveException;
import bf.publichealth.modules.prescription.domain.QuantityExceededException;
import bf.publichealth.modules.prescription.domain.StatutPrescription;

/**
 * Règles de dispensation — le domaine pur, sans la moindre
 * infrastructure. La pharmacie du BF fonctionne par retraits fractionnés :
 * le cumul ne peut jamais excéder le prescrit.
 */
class PrescriptionRulesTest {

    private static final BigDecimal PRESCRIT = new BigDecimal("15.00");

    // ------------------------------------------------------------------
    // Restant dispensable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Restant sans aucune dispensation = quantité prescrite")
    void restantSansDispensation() {
        assertThat(DispensingRules.restant(PRESCRIT, null))
                .isEqualByComparingTo("15.00");
        assertThat(DispensingRules.restant(PRESCRIT, BigDecimal.ZERO))
                .isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("Restant après dispensations partielles CUMULÉES")
    void restantApresDispensationsCumulees() {
        // 5.00 puis 3.00 délivrés sur 15.00 prescrits : il reste 7.00.
        BigDecimal cumul = new BigDecimal("5.00").add(new BigDecimal("3.00"));
        assertThat(DispensingRules.restant(PRESCRIT, cumul))
                .isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("Restant épuisé = 0 exactement")
    void restantEpuise() {
        assertThat(DispensingRules.restant(PRESCRIT, new BigDecimal("15.00")))
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // Vérification avant dispensation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Dispensation exacte au restant : autorisée (dernière délivrance)")
    void dispensationExacteAuRestantAutorisee() {
        assertThatCode(() -> DispensingRules.verifierAvantDispensation(
                StatutPrescription.ACTIVE, PRESCRIT, new BigDecimal("8.00"),
                new BigDecimal("7.00")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Dépassement refusé : l'exception porte le restant et le demandé exacts")
    void depassementRefuseAvecDetailExact() {
        // 15.00 prescrits, 8.00 déjà délivrés, 7.00 demandés : restant 7.00... on
        // teste le dépassement franc : 9.00 demandés sur restant 7.00.
        assertThatThrownBy(() -> DispensingRules.verifierAvantDispensation(
                StatutPrescription.ACTIVE, PRESCRIT, new BigDecimal("8.00"),
                new BigDecimal("9.00")))
                .isInstanceOf(QuantityExceededException.class)
                .hasMessageContaining("restant 7.00")
                .hasMessageContaining("demandé 9.00")
                .satisfies(e -> {
                    QuantityExceededException depassement = (QuantityExceededException) e;
                    assertThat(depassement.getRestant()).isEqualByComparingTo("7.00");
                    assertThat(depassement.getDemandee()).isEqualByComparingTo("9.00");
                });
    }

    @Test
    @DisplayName("Prescription annulée : dispensation refusée, statut embarqué")
    void prescriptionAnnuleeRefusee() {
        assertThatThrownBy(() -> DispensingRules.verifierAvantDispensation(
                StatutPrescription.CANCELLED, PRESCRIT, null, new BigDecimal("1.00")))
                .isInstanceOf(PrescriptionInactiveException.class)
                .hasMessageContaining("cancelled")
                .satisfies(e -> assertThat(((PrescriptionInactiveException) e).getStatut())
                        .isEqualTo(StatutPrescription.CANCELLED));
    }

    @Test
    @DisplayName("Prescription en erreur de saisie : dispensation refusée pareillement")
    void prescriptionEnErreurRefusee() {
        assertThatThrownBy(() -> DispensingRules.verifierAvantDispensation(
                StatutPrescription.ENTERED_IN_ERROR, PRESCRIT, null, new BigDecimal("1.00")))
                .isInstanceOf(PrescriptionInactiveException.class)
                .hasMessageContaining("entered-in-error");
    }

    @Test
    @DisplayName("Quantité strictement positive : zéro et négatif refusés, null refusé")
    void quantiteStrictementPositive() {
        for (BigDecimal quantite : new BigDecimal[] {null, BigDecimal.ZERO,
                new BigDecimal("-1.00")}) {
            assertThatThrownBy(() -> DispensingRules.verifierAvantDispensation(
                    StatutPrescription.ACTIVE, PRESCRIT, null, quantite))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictement positive");
        }
    }

    // ------------------------------------------------------------------
    // Statuts : machine à contre-entrées
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Seule une prescription active peut être annulée ou passée en erreur")
    void transitionsDepuisActivesUniquement() {
        assertThatCode(() -> StatutPrescription.ACTIVE
                .exigerTransitionVers(StatutPrescription.CANCELLED))
                .doesNotThrowAnyException();
        assertThatCode(() -> StatutPrescription.ACTIVE
                .exigerTransitionVers(StatutPrescription.ENTERED_IN_ERROR))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Un statut terminal ne bouge plus : ré-annulation = transition illégale")
    void statutTerminalImmobile() {
        for (StatutPrescription terminal : new StatutPrescription[] {
                StatutPrescription.CANCELLED, StatutPrescription.ENTERED_IN_ERROR}) {
            assertThatThrownBy(() -> terminal
                    .exigerTransitionVers(StatutPrescription.CANCELLED))
                    .isInstanceOf(IllegalPrescriptionTransitionException.class)
                    .satisfies(e -> {
                        IllegalPrescriptionTransitionException transition =
                                (IllegalPrescriptionTransitionException) e;
                        assertThat(transition.getFrom()).isEqualTo(terminal);
                        assertThat(transition.getTo()).isEqualTo(StatutPrescription.CANCELLED);
                    });
        }
    }

    @Test
    @DisplayName("Résolution des codes persistés (CHECK V8) et rejet des inconnus")
    void resolutionDesCodes() {
        assertThat(StatutPrescription.depuisCode("active")).isEqualTo(StatutPrescription.ACTIVE);
        assertThat(StatutPrescription.depuisCode("entered-in-error"))
                .isEqualTo(StatutPrescription.ENTERED_IN_ERROR);
        assertThatThrownBy(() -> StatutPrescription.depuisCode("revoked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconnu");
    }
}
