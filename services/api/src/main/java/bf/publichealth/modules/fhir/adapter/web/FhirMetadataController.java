package bf.publichealth.modules.fhir.adapter.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.fhir.application.FhirMappers;

/**
 * Façade FHIR R4 — GET /fhir/R4/metadata : CapabilityStatement.
 * Il déclare EXACTEMENT ce que la façade livre (read + search-type,
 * lecture seule) — jamais un vœu.
 */
@RestController
public class FhirMetadataController {

    /**
     * Version logicielle exposée aux partenaires — miroir du pom.xml
     * (public-health-api 0.1.0-SNAPSHOT) ; constante délibérée : le
     * CapabilityStatement est un contrat, pas une sonde de build.
     */
    private static final String VERSION_LOGICIEL = "0.1.0";

    private final FhirEncodeur encodeur;

    public FhirMetadataController(FhirEncodeur encodeur) {
        this.encodeur = encodeur;
    }

    @GetMapping(value = "/fhir/R4/metadata", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> metadata(
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(FhirMappers.versCapabilityStatement(VERSION_LOGICIEL)));
    }
}
