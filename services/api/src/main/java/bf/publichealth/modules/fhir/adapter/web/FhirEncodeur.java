package bf.publichealth.modules.fhir.adapter.web;

import org.hl7.fhir.r4.model.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import bf.publichealth.modules.fhir.domain.FormatFhirNonSupporteException;

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Utilitaires web de la façade : encodage JSON FHIR (via le contexte R4
 * partagé de {@code FhirConfig}), type de média {@code application/fhir+json},
 * base absolue des URL, contrôle de {@code _format}.
 */
@Component
public class FhirEncodeur {

    /** Le seul média de la façade E7 (CapabilityStatement : format [json]). */
    public static final String FHIR_JSON = "application/fhir+json";

    public static final MediaType FHIR_JSON_MEDIA = MediaType.parseMediaType(FHIR_JSON);

    private static final String CHEMIN_BASE = "/fhir/R4";

    private final FhirContext contexte;

    public FhirEncodeur(FhirContext contexte) {
        this.contexte = contexte;
    }

    /** Encode une ressource R4 en JSON FHIR (sérialiseur HAPI). */
    public String json(Resource ressource) {
        return contexte.newJsonParser().encodeResourceToString(ressource);
    }

    /**
     * Base absolue de la façade déduite de la requête entrante
     * (ex. {@code http://hote.bf/fhir/R4}) — sert aux fullUrl des Bundle
     * entries et aux liens self/next.
     */
    public static String baseUrl(HttpServletRequest requete) {
        String url = requete.getRequestURL().toString();
        int index = url.indexOf(CHEMIN_BASE);
        return index >= 0 ? url.substring(0, index + CHEMIN_BASE.length()) : url;
    }

    /**
     * {@code _format} : seul JSON est parlé. Les alias json/application/json/
     * application/fhir+json passent ; tout le reste (xml, ttl…) est refusé
     * en 400 not-supported, pas ignoré en silence.
     */
    public static void verifierFormatSupporte(String format) {
        if (format == null || format.isBlank()) {
            return;
        }
        if (!format.toLowerCase().contains("json")) {
            throw new FormatFhirNonSupporteException(format);
        }
    }
}
