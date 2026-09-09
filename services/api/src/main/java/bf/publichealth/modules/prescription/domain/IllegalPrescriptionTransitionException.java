package bf.publichealth.modules.prescription.domain;

/**
 * Transition illégale tentée sur une prescription (ré-annulation d'une
 * prescription déjà annulée, contre-entrée sur une prescription en erreur,
 * réveil d'un statut terminal). Le domaine refuse — l'historique est
 * intact — et la couche API répond 409 (problem+json, RFC 7807).
 */
public class IllegalPrescriptionTransitionException extends RuntimeException {

    private final StatutPrescription from;
    private final StatutPrescription to;

    public IllegalPrescriptionTransitionException(StatutPrescription from, StatutPrescription to) {
        super("Transition de prescription illégale : %s ne peut pas évoluer vers %s — "
                + "seule une prescription active peut être annulée ou passée en erreur"
                .formatted(from.getCode(), to.getCode()));
        this.from = from;
        this.to = to;
    }

    public StatutPrescription getFrom() {
        return from;
    }

    public StatutPrescription getTo() {
        return to;
    }
}
