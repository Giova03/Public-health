package bf.publichealth.modules.fhir.domain;

/**
 * Paramètre de recherche FHIR invalide — 400, OperationOutcome issue
 * {@code invalid} (équivalent FHIR du 400 RFC 7807).
 */
public class ParametreFhirInvalideException extends RuntimeException {

    public ParametreFhirInvalideException(String message) {
        super(message);
    }
}
