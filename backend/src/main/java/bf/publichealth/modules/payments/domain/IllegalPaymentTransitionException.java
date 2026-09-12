package bf.publichealth.modules.payments.domain;

/**
 * Transition illégale tentée sur un paiement (rétrogradation d'état,
 * saut d'état, état terminal). Le domaine refuse — l'historique est
 * intact — et la couche API répond 409 (problem+json, RFC 7807).
 */
public class IllegalPaymentTransitionException extends RuntimeException {

    private final PaymentState from;
    private final PaymentState to;

    public IllegalPaymentTransitionException(PaymentState from, PaymentState to) {
        super("Transition de paiement illégale : %s ne peut pas évoluer vers %s"
                .formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public PaymentState getFrom() {
        return from;
    }

    public PaymentState getTo() {
        return to;
    }
}
