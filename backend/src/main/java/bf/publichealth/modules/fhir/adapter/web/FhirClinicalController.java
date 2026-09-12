package bf.publichealth.modules.fhir.adapter.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.fhir.application.FhirFacadeService;
import bf.publichealth.modules.fhir.application.FhirPagination;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Façade FHIR R4 — ressources cliniques et pharmacologiques : Encounter,
 * Observation, Condition, MedicationRequest. Read + search par patient
 * (Observation accepte aussi encounter). LECTURE SEULE.
 *
 * <p>MedicationRequest : une ressource PAR LIGNE de prescription E3
 * (id = prescription_item.id), statut dérivé — voir FhirMappers.</p>
 */
@RestController
public class FhirClinicalController {

    private final FhirFacadeService facade;
    private final FhirEncodeur encodeur;

    public FhirClinicalController(FhirFacadeService facade, FhirEncodeur encodeur) {
        this.facade = facade;
        this.encodeur = encodeur;
    }

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    /** GET /fhir/R4/Encounter?patient={id}&_count=&page[offset]= */
    @GetMapping(value = "/fhir/R4/Encounter", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> rechercherEncounters(
            @RequestParam(required = false) String patient,
            @RequestParam(name = "_count", required = false) String count,
            @RequestParam(name = "page[offset]", required = false) String offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest requete) {
        FhirEncodeur.verifierFormatSupporte(format);
        FhirPagination page = FhirPagination.depuis(count, offset);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.rechercherEncounters(
                        patient, page, FhirEncodeur.baseUrl(requete))));
    }

    /** GET /fhir/R4/Encounter/{id} */
    @GetMapping(value = "/fhir/R4/Encounter/{id}", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> lireEncounter(@PathVariable UUID id,
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.lireEncounter(id)));
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /** GET /fhir/R4/Observation?patient={id} et/ou ?encounter={id} */
    @GetMapping(value = "/fhir/R4/Observation", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> rechercherObservations(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String encounter,
            @RequestParam(name = "_count", required = false) String count,
            @RequestParam(name = "page[offset]", required = false) String offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest requete) {
        FhirEncodeur.verifierFormatSupporte(format);
        FhirPagination page = FhirPagination.depuis(count, offset);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.rechercherObservations(
                        patient, encounter, page, FhirEncodeur.baseUrl(requete))));
    }

    /** GET /fhir/R4/Observation/{id} */
    @GetMapping(value = "/fhir/R4/Observation/{id}", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> lireObservation(@PathVariable UUID id,
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.lireObservation(id)));
    }

    // ------------------------------------------------------------------
    // Condition
    // ------------------------------------------------------------------

    /** GET /fhir/R4/Condition?patient={id} */
    @GetMapping(value = "/fhir/R4/Condition", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> rechercherConditions(
            @RequestParam(required = false) String patient,
            @RequestParam(name = "_count", required = false) String count,
            @RequestParam(name = "page[offset]", required = false) String offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest requete) {
        FhirEncodeur.verifierFormatSupporte(format);
        FhirPagination page = FhirPagination.depuis(count, offset);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.rechercherConditions(
                        patient, page, FhirEncodeur.baseUrl(requete))));
    }

    /** GET /fhir/R4/Condition/{id} */
    @GetMapping(value = "/fhir/R4/Condition/{id}", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> lireCondition(@PathVariable UUID id,
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.lireCondition(id)));
    }

    // ------------------------------------------------------------------
    // MedicationRequest (prescriptions E3, par ligne)
    // ------------------------------------------------------------------

    /** GET /fhir/R4/MedicationRequest?patient={id}&status={active|completed|cancelled|entered-in-error} */
    @GetMapping(value = "/fhir/R4/MedicationRequest", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> rechercherMedicationRequests(
            @RequestParam(required = false) String patient,
            @RequestParam(required = false) String status,
            @RequestParam(name = "_count", required = false) String count,
            @RequestParam(name = "page[offset]", required = false) String offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest requete) {
        FhirEncodeur.verifierFormatSupporte(format);
        FhirPagination page = FhirPagination.depuis(count, offset);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.rechercherMedicationRequests(
                        patient, status, page, FhirEncodeur.baseUrl(requete))));
    }

    /** GET /fhir/R4/MedicationRequest/{id} — id = prescription_item.id (E3). */
    @GetMapping(value = "/fhir/R4/MedicationRequest/{id}", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> lireMedicationRequest(@PathVariable UUID id,
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.lireMedicationRequest(id)));
    }
}
