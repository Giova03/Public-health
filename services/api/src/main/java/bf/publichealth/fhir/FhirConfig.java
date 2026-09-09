package bf.publichealth.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Façade FHIR R4 (ADR-002) : FHIR est un CONTRAT d'interopérabilité,
 * jamais le modèle de stockage. Le domaine reste souverain ;
 * seuls fhir-adapter et hub ont le droit de parler « étranger ».
 *
 * <p>Sprint 0 : contexte R4 partagé (thread-safe). La façade
 * lecture/recherche des 19 ressources arrive avec l'épique E7,
 * avec le validateur HAPI branché dans la CI.</p>
 */
@Configuration
public class FhirConfig {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }
}
