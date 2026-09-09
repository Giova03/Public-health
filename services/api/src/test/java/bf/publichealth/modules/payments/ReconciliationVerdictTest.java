package bf.publichealth.modules.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.payments.domain.PaymentState;
import bf.publichealth.modules.payments.domain.ReconciliationVerdict;
import bf.publichealth.modules.payments.domain.ReconciliationVerdict.Action;
import bf.publichealth.modules.payments.domain.StatutPrestataire;

/**
 * Unitaires du verdict de réconciliation — TOUTES les branches de la
 * matrice (ADR-006 : le prestataire fait foi, l'état interne ne recule
 * JAMAIS) + le chemin avant légal (aucun raccourci dans la machine à
 * états).
 */
class ReconciliationVerdictTest {

    private static ReconciliationVerdict verdict(PaymentState interne, StatutPrestataire externe) {
        return ReconciliationVerdict.pour(interne, externe);
    }

    // ------------------------------------------------------------------
    // Avancées — le prestataire est devant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PENDING + prestataire SUCCEEDED → AVANCER (l'encaissement à faire)")
    void avancerVersSucces() {
        for (PaymentState interne : List.of(PaymentState.INITIATED, PaymentState.PENDING,
                PaymentState.AUTHORIZED)) {
            var v = verdict(interne, StatutPrestataire.SUCCEEDED);
            assertThat(v.action()).as("interne %s", interne).isEqualTo(Action.AVANCER);
            assertThat(v.cible()).isEqualTo(PaymentState.SUCCEEDED);
            assertThat(v.natureEcart()).isNull();
        }
    }

    @Test
    @DisplayName("INITIATED + prestataire PENDING → AVANCER (rattrapage interne)")
    void avancerVersPending() {
        var v = verdict(PaymentState.INITIATED, StatutPrestataire.PENDING);
        assertThat(v.action()).isEqualTo(Action.AVANCER);
        assertThat(v.cible()).isEqualTo(PaymentState.PENDING);
    }

    @Test
    @DisplayName("Prestataire FAILED → AVANCER vers FAILED depuis tout état movable")
    void avancerVersEchec() {
        for (PaymentState interne : List.of(PaymentState.INITIATED, PaymentState.PENDING,
                PaymentState.AUTHORIZED)) {
            var v = verdict(interne, StatutPrestataire.FAILED);
            assertThat(v.action()).as("interne %s", interne).isEqualTo(Action.AVANCER);
            assertThat(v.cible()).isEqualTo(PaymentState.FAILED);
        }
    }

    @Test
    @DisplayName("Prestataire CANCELLED → AVANCER depuis INITIATED/PENDING, IGNORER depuis AUTHORIZED")
    void annulationPrestataire() {
        assertThat(verdict(PaymentState.INITIATED, StatutPrestataire.CANCELLED).cible())
                .isEqualTo(PaymentState.CANCELLED);
        assertThat(verdict(PaymentState.PENDING, StatutPrestataire.CANCELLED).cible())
                .isEqualTo(PaymentState.CANCELLED);
        assertThat(verdict(PaymentState.AUTHORIZED, StatutPrestataire.CANCELLED).action())
                .isEqualTo(Action.IGNORER);
    }

    // ------------------------------------------------------------------
    // Confirmations — états alignés
    // ------------------------------------------------------------------

    @Test
    @DisplayName("États alignés → CONFIRMER (SUCCEEDED/SUCCEEDED, PENDING/PENDING)")
    void confirmations() {
        assertThat(verdict(PaymentState.SUCCEEDED, StatutPrestataire.SUCCEEDED).action())
                .isEqualTo(Action.CONFIRMER);
        assertThat(verdict(PaymentState.PENDING, StatutPrestataire.PENDING).action())
                .isEqualTo(Action.CONFIRMER);
    }

    // ------------------------------------------------------------------
    // Ignorés — forward-only, le fait accompli interne reste
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SUCCEEDED + prestataire PENDING → IGNORER : écart journalisé, jamais d'exception")
    void retrogradationIgnoree() {
        var v = verdict(PaymentState.SUCCEEDED, StatutPrestataire.PENDING);
        assertThat(v.action()).isEqualTo(Action.IGNORER);
        assertThat(v.natureEcart()).isEqualTo(ReconciliationVerdict.ECART_RETROGRADATION);
        assertThat(v.cible()).isNull();
    }

    @Test
    @DisplayName("AUTHORIZED + prestataire PENDING → IGNORER : le fait accompli reste")
    void authorizedContrePending() {
        assertThat(verdict(PaymentState.AUTHORIZED, StatutPrestataire.PENDING).action())
                .isEqualTo(Action.IGNORER);
    }

    @Test
    @DisplayName("SUCCEEDED + prestataire FAILED/CANCELLED → IGNORER : contradiction tracée")
    void succesContreEchec() {
        for (StatutPrestataire externe : List.of(StatutPrestataire.FAILED,
                StatutPrestataire.CANCELLED)) {
            var v = verdict(PaymentState.SUCCEEDED, externe);
            assertThat(v.action()).as("externe %s", externe).isEqualTo(Action.IGNORER);
            assertThat(v.natureEcart()).isEqualTo(ReconciliationVerdict.ECART_RETROGRADATION);
        }
    }

    @Test
    @DisplayName("État scellé + prestataire SUCCEEDED → IGNORER : le fait accompli interne reste")
    void etatScelleContreSucces() {
        var v = verdict(PaymentState.FAILED, StatutPrestataire.SUCCEEDED);
        assertThat(v.action()).isEqualTo(Action.IGNORER);
        assertThat(v.natureEcart()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Inconnu — on n'en déduit rien
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Prestataire INCONNU → EXAMINER quel que soit l'état interne")
    void etatInconnu() {
        for (PaymentState interne : List.of(PaymentState.INITIATED, PaymentState.PENDING,
                PaymentState.AUTHORIZED, PaymentState.SUCCEEDED)) {
            var v = verdict(interne, StatutPrestataire.INCONNU);
            assertThat(v.action()).as("interne %s", interne).isEqualTo(Action.EXAMINER);
            assertThat(v.natureEcart()).isEqualTo(ReconciliationVerdict.ECART_ETAT_INCONNU);
            assertThat(v.cible()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // Chemin avant légal — jamais de raccourci
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PENDING→SUCCEEDED : deux pas légaux (AUTHORIZED), jamais de saut")
    void cheminPendingVersSucces() {
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.PENDING,
                PaymentState.SUCCEEDED))
                .containsExactly(PaymentState.AUTHORIZED, PaymentState.SUCCEEDED);
    }

    @Test
    @DisplayName("INITIATED→SUCCEEDED : trois pas légaux")
    void cheminInitiatedVersSucces() {
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.INITIATED,
                PaymentState.SUCCEEDED))
                .containsExactly(PaymentState.PENDING, PaymentState.AUTHORIZED,
                        PaymentState.SUCCEEDED);
    }

    @Test
    @DisplayName("AUTHORIZED→SUCCEEDED et INITIATED→PENDING : un pas direct")
    void cheminsDirects() {
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.AUTHORIZED,
                PaymentState.SUCCEEDED))
                .containsExactly(PaymentState.SUCCEEDED);
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.INITIATED,
                PaymentState.PENDING))
                .containsExactly(PaymentState.PENDING);
    }

    @Test
    @DisplayName("Cible déjà atteinte ou inaccessible → chemin vide")
    void cheminsImpossibles() {
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.SUCCEEDED,
                PaymentState.SUCCEEDED)).isEmpty();
        // Rétrogradation : AUCUN chemin (forward-only).
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.SUCCEEDED,
                PaymentState.PENDING)).isEmpty();
        // Terminal : plus rien ne bouge.
        assertThat(ReconciliationVerdict.cheminAvant(PaymentState.RECONCILED,
                PaymentState.SUCCEEDED)).isEmpty();
    }

    @Test
    @DisplayName("Chemin vers FAILED depuis tout état movable : un pas direct")
    void cheminVersEchec() {
        for (PaymentState interne : List.of(PaymentState.INITIATED, PaymentState.PENDING,
                PaymentState.AUTHORIZED)) {
            assertThat(ReconciliationVerdict.cheminAvant(interne, PaymentState.FAILED))
                    .as("depuis %s", interne)
                    .containsExactly(PaymentState.FAILED);
        }
    }
}
