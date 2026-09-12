package bf.publichealth.modules.fhir.domain;

/**
 * Format de représentation demandé non supporté ({@code _format=xml}…) — 400,
 * OperationOutcome issue {@code not-supported}. La façade E7 ne parle que
 * JSON ({@code application/fhir+json}).
 */
public class FormatFhirNonSupporteException extends RuntimeException {

    public FormatFhirNonSupporteException(String format) {
        super("Format non supporté : %s — la façade FHIR n'échange qu'en JSON (application/fhir+json)"
                .formatted(format));
    }
}
