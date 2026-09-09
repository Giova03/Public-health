package bf.publichealth.modules.prescription.domain;

/**
 * Dispensation tentée sur une prescription non active (annulée ou passée
 * en erreur de saisie). Le domaine refuse — la prescription est un fait
 * historique — et la couche API répond 409 (problem+json, RFC 7807).
 */
public class PrescriptionInactiveException extends RuntimeException {

    private final StatutPrescription statut;

    public PrescriptionInactiveException(StatutPrescription statut) {
        super("Prescription non active (statut : %s) — la dispensation est refusée"
                .formatted(statut.getCode()));
        this.statut = statut;
    }

    public StatutPrescription getStatut() {
        return statut;
    }
}
