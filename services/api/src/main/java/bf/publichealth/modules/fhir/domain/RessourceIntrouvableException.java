package bf.publichealth.modules.fhir.domain;

/**
 * Ressource FHIR demandée inexistante — 404, OperationOutcome issue
 * {@code not-found} (mappé depuis la sémantique RFC 7807 « introuvable »).
 */
public class RessourceIntrouvableException extends RuntimeException {

    public RessourceIntrouvableException(String typeRessource, Object identifiant) {
        super("Ressource introuvable : %s/%s".formatted(typeRessource, identifiant));
    }
}
