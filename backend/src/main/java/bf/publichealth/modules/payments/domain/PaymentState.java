package bf.publichealth.modules.payments.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Machine à états du paiement — ADR-006.
 *
 * <p>Huit états, transitions forward-only : un état ne recule JAMAIS.
 * La réconciliation nocturne est le seul chemin vers RECONCILED et
 * c'est elle qui fait foi, pas le webhook (qui ne fait qu'accélérer).</p>
 *
 * <pre>
 * INITIATED ──▶ PENDING ──▶ AUTHORIZED ──▶ SUCCEEDED ──▶ RECONCILED
 *                 │            │              │
 *                 ▼            ▼              ▼
 *             CANCELLED      FAILED        REFUNDED ──▶ RECONCILED
 * </pre>
 */
public enum PaymentState {

    INITIATED,
    PENDING,
    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    RECONCILED;

    private static final Map<PaymentState, Set<PaymentState>> LEGAL_TRANSITIONS = Map.of(
            INITIATED, EnumSet.of(PENDING, CANCELLED, FAILED),
            PENDING, EnumSet.of(AUTHORIZED, FAILED, CANCELLED),
            AUTHORIZED, EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, EnumSet.of(REFUNDED, RECONCILED),
            FAILED, EnumSet.of(RECONCILED),
            CANCELLED, EnumSet.of(RECONCILED),
            REFUNDED, EnumSet.of(RECONCILED),
            RECONCILED, EnumSet.noneOf(PaymentState.class));

    /** Un état terminal de réconciliation ne bouge plus, jamais. */
    public boolean isTerminal() {
        return this == RECONCILED;
    }

    public boolean canTransitionTo(PaymentState target) {
        return LEGAL_TRANSITIONS.get(this).contains(target);
    }

    /**
     * Vérifie la légalité de la transition, sinon lève
     * {@link IllegalPaymentTransitionException}. Toute tentative illégale
     * est journalisée par l'appelant (audit six dimensions, résultat DENIED).
     */
    public void requireTransitionTo(PaymentState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(this, target);
        }
    }
}
