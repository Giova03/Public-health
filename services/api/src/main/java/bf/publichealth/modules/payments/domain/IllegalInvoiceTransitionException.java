package bf.publichealth.modules.payments.domain;

/**
 * Transition de facture illégale — 409, l'historique reste intact.
 * Exemples : ré-annulation d'une facture voided, émission d'une facture
 * déjà émise, annulation d'une facture encaissée.
 */
public class IllegalInvoiceTransitionException extends RuntimeException {

    private final StatutFacture from;
    private final StatutFacture to;

    public IllegalInvoiceTransitionException(StatutFacture from, StatutFacture to) {
        super("Transition de facture illégale : %s → %s (voided et paid sont terminaux)".formatted(
                from.getCode(), to.getCode()));
        this.from = from;
        this.to = to;
    }

    public StatutFacture getFrom() {
        return from;
    }

    public StatutFacture getTo() {
        return to;
    }
}
