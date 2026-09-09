package bf.publichealth.modules.prescription.domain;

/**
 * Ressource du module prescription introuvable : prescription inconnue,
 * ou ligne absente de la prescription visée. La couche API répond 404
 * (problem+json, RFC 7807) — jamais de divulgation au-delà du message.
 */
public class PrescriptionIntrouvableException extends RuntimeException {

    public PrescriptionIntrouvableException(String message) {
        super(message);
    }
}
