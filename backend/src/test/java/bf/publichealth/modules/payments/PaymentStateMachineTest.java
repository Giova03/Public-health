package bf.publichealth.modules.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.payments.domain.IllegalPaymentTransitionException;
import bf.publichealth.modules.payments.domain.PaymentState;

/**
 * La machine à états est la loi du paiement : forward-only, sans exception.
 * Scénario critique couvert : « un état ne recule jamais ».
 */
class PaymentStateMachineTest {

    @Test
    @DisplayName("Le chemin nominal complet est légal jusqu'à la réconciliation")
    void nominalPathIsLegal() {
        assertThat(PaymentState.INITIATED.canTransitionTo(PaymentState.PENDING)).isTrue();
        assertThat(PaymentState.PENDING.canTransitionTo(PaymentState.AUTHORIZED)).isTrue();
        assertThat(PaymentState.AUTHORIZED.canTransitionTo(PaymentState.SUCCEEDED)).isTrue();
        assertThat(PaymentState.SUCCEEDED.canTransitionTo(PaymentState.RECONCILED)).isTrue();
    }

    @Test
    @DisplayName("Les branches d'exception sont légales et convergent vers RECONCILED")
    void branchesAreLegal() {
        assertThat(PaymentState.PENDING.canTransitionTo(PaymentState.FAILED)).isTrue();
        assertThat(PaymentState.PENDING.canTransitionTo(PaymentState.CANCELLED)).isTrue();
        assertThat(PaymentState.AUTHORIZED.canTransitionTo(PaymentState.FAILED)).isTrue();
        assertThat(PaymentState.SUCCEEDED.canTransitionTo(PaymentState.REFUNDED)).isTrue();
        assertThat(PaymentState.REFUNDED.canTransitionTo(PaymentState.RECONCILED)).isTrue();
        assertThat(PaymentState.FAILED.canTransitionTo(PaymentState.RECONCILED)).isTrue();
        assertThat(PaymentState.CANCELLED.canTransitionTo(PaymentState.RECONCILED)).isTrue();
    }

    @Test
    @DisplayName("Aucun état ne recule jamais — le webhook tardif est impuissant")
    void noRegressionEver() {
        for (PaymentState from : PaymentState.values()) {
            for (PaymentState to : PaymentState.values()) {
                boolean earlier = to.ordinal() < from.ordinal();
                if (earlier) {
                    assertThat(from.canTransitionTo(to))
                            .as("%s ne doit jamais reculer vers %s", from, to)
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("RECONCILED est terminal : plus rien ne bouge")
    void reconciledIsTerminal() {
        assertThat(PaymentState.RECONCILED.isTerminal()).isTrue();
        for (PaymentState to : PaymentState.values()) {
            assertThat(PaymentState.RECONCILED.canTransitionTo(to)).isFalse();
        }
    }

    @Test
    @DisplayName("Interdire les sauts d'état (INITIATED ne saute pas à SUCCEEDED)")
    void noSkipping() {
        assertThat(PaymentState.INITIATED.canTransitionTo(PaymentState.SUCCEEDED)).isFalse();
        assertThat(PaymentState.INITIATED.canTransitionTo(PaymentState.AUTHORIZED)).isFalse();
        assertThat(PaymentState.PENDING.canTransitionTo(PaymentState.SUCCEEDED)).isFalse();
    }

    @Test
    @DisplayName("requireTransitionTo lève une exception sur transition illégale")
    void requireThrows() {
        assertThatThrownBy(() -> PaymentState.SUCCEEDED.requireTransitionTo(PaymentState.PENDING))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }
}
