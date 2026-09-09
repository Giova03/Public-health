package bf.publichealth.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * Validateur HAPI en garde de CI (ADR-002) : tout exemple FHIR R4 que la
 * façade échangera doit passer la validation d'instance. La façade
 * lecture/recherche des 19 ressources arrive avec l'épique E7 — ces
 * exemples fixent dès maintenant le niveau d'exigence.
 *
 * <p>Le module FhirInstanceValidator est enregistré explicitement ; les
 * validateurs XSD/schematron standards sont désactivés (les schémas ne
 * sont plus embarqués dans les artefacts HAPI 7.x). Le contrôle négatif
 * prouve que la validation mord vraiment.</p>
 */
class FhirValidationTest {

    private final FhirContext contexte = FhirContext.forR4Cached();

    private FhirValidator validateur() {
        FhirValidator validateur = contexte.newValidator();
        validateur.setValidateAgainstStandardSchema(false);
        validateur.setValidateAgainstStandardSchematron(false);
        validateur.registerValidatorModule(
                new org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator(contexte));
        return validateur;
    }

    @Test
    @DisplayName("Patient R4 conforme au profil de base")
    void patientConforme() throws IOException {
        Patient patient = (Patient) contexte.newJsonParser()
                .parseResource(lecture("patient-exemple.json"));

        ValidationResult resultat = validateur().validateWithResult(patient);

        assertThat(resultat.isSuccessful())
                .as("%s", resultat.getMessages())
                .isTrue();
    }

    @Test
    @DisplayName("Observation R4 conforme au profil de base")
    void observationConforme() throws IOException {
        Observation observation = (Observation) contexte.newJsonParser()
                .parseResource(lecture("observation-exemple.json"));

        ValidationResult resultat = validateur().validateWithResult(observation);

        assertThat(resultat.isSuccessful())
                .as("%s", resultat.getMessages())
                .isTrue();
    }

    @Test
    @DisplayName("Contrôle négatif : une Observation incomplète est rejetée")
    void controleNegatif() {
        // Ni statut (obligatoire) ni code (obligatoire) : doit échouer.
        Observation incomplete = new Observation();

        ValidationResult resultat = validateur().validateWithResult(incomplete);

        assertThat(resultat.isSuccessful()).isFalse();
    }

    private String lecture(String nom) throws IOException {
        try (var flux = getClass().getResourceAsStream("/fhir/" + nom)) {
            if (flux == null) {
                throw new IOException("Ressource FHIR introuvable : " + nom);
            }
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
