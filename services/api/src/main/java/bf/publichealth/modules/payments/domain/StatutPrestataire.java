package bf.publichealth.modules.payments.domain;

/**
 * Statut du paiement VU PAR LE PRESTATAIRE (FedaPay) lors de la
 * réconciliation. Domaine pur : le verdict de réconciliation se décide
 * sur ce type sans dépendance à l'infrastructure.
 *
 * <p>{@link #INCONNU} = le prestataire n'a pas répondu ou la référence
 * est inconnue de lui : on n'en déduit RIEN, on examine.</p>
 */
public enum StatutPrestataire {

    SUCCEEDED,
    PENDING,
    FAILED,
    CANCELLED,
    INCONNU
}
