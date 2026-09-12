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
 * Façade FHIR R4 — Patient : read (404 inconnu, 410 fusionné) et recherche
 * miroir du MPI (mêmes critères que l'API identity E1, exposés en search
 * params FHIR). LECTURE SEULE : aucun verbe d'écriture sur /fhir/**.
 */
@RestController
public class FhirPatientController {

    private final FhirFacadeService facade;
    private final FhirEncodeur encodeur;

    public FhirPatientController(FhirFacadeService facade, FhirEncodeur encodeur) {
        this.facade = facade;
        this.encodeur = encodeur;
    }

    /**
     * GET /fhir/R4/Patient?family=&amp;given=&amp;phone=&amp;birthdate=&amp;identifier=&amp;_count=&amp;page[offset]=
     * → Bundle searchset. Résultat vide = Bundle vide (total 0), JAMAIS une erreur.
     */
    @GetMapping(value = "/fhir/R4/Patient", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> rechercher(
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String given,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String birthdate,
            @RequestParam(required = false) String identifier,
            @RequestParam(name = "_count", required = false) String count,
            @RequestParam(name = "page[offset]", required = false) String offset,
            @RequestParam(name = "_format", required = false) String format,
            HttpServletRequest requete) {
        FhirEncodeur.verifierFormatSupporte(format);
        FhirPagination page = FhirPagination.depuis(count, offset);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.rechercherPatients(
                        family, given, phone, birthdate, identifier, page,
                        FhirEncodeur.baseUrl(requete))));
    }

    /** GET /fhir/R4/Patient/{id} — 404 OperationOutcome si inconnu, 410 si fusionné (master-id). */
    @GetMapping(value = "/fhir/R4/Patient/{id}", produces = FhirEncodeur.FHIR_JSON)
    public ResponseEntity<String> lire(@PathVariable UUID id,
            @RequestParam(name = "_format", required = false) String format) {
        FhirEncodeur.verifierFormatSupporte(format);
        return ResponseEntity.ok()
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(facade.lirePatient(id)));
    }
}
