package bf.publichealth.modules.fhir.adapter.web;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.fhir.domain.DossierFusionneException;
import bf.publichealth.modules.fhir.domain.FormatFhirNonSupporteException;
import bf.publichealth.modules.fhir.domain.ParametreFhirInvalideException;
import bf.publichealth.modules.fhir.domain.RessourceIntrouvableException;
import java.util.UUID;

/**
 * Erreurs de la façade en OperationOutcome (le « problem+json » de FHIR),
 * sémantique RFC 7807 conservée : 404 → not-found, 410 → deleted
 * (absorbé par fusion — voir note), 400 → invalid / not-supported,
 * 500 → exception.
 *
 * <p>NOTE 410 : le code issue-type {@code gone} n'existe pas dans
 * l'énumération HAPI R4 embarquée (org.hl7.fhir.r4 6.3.11) — il serait
 * d'ailleurs IMPARSABLE par tout consommateur HAPI. Le 410 porte donc le
 * code {@code deleted} (« la ressource référencée a été absorbée »), plus
 * l'extension master-id qui pointe le dossier maître.</p>
 *
 * <p>Conseil local au module fhir : assigné aux seuls contrôleurs de la
 * façade (pas de GlobalExceptionHandler modifié, les autres modules restent
 * en problem+json) et prioritaire (HIGHEST_PRECEDENCE) pour prendre la
 * main avant le gestionnaire global sur /fhir/R4/**.</p>
 */
@RestControllerAdvice(assignableTypes = {FhirPatientController.class,
        FhirClinicalController.class, FhirMetadataController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FhirOperationOutcomeAdvice {

    /**
     * Extension du 410 : le dossier maître, en RÉFÉRENCE —
     * le consommateur suit sans deviner.
     */
    static final String EXTENSION_MASTER_ID =
            "https://publichealth.bf/fhir/StructureDefinition/master-id";

    private static final Logger LOG = LoggerFactory.getLogger(FhirOperationOutcomeAdvice.class);

    private final FhirEncodeur encodeur;

    public FhirOperationOutcomeAdvice(FhirEncodeur encodeur) {
        this.encodeur = encodeur;
    }

    @ExceptionHandler(RessourceIntrouvableException.class)
    public ResponseEntity<String> introuvable(RessourceIntrouvableException e) {
        return probleme(HttpStatus.NOT_FOUND, OperationOutcome.IssueType.NOTFOUND, e.getMessage());
    }

    @ExceptionHandler(DossierFusionneException.class)
    public ResponseEntity<String> fusionne(DossierFusionneException e) {
        // 410 Gone : code issue 'deleted' (absorbé par fusion, 'gone' inexistant
        // côté énum HAPI — voir note de classe) + extension master-id.
        OperationOutcome outcome = outcome(OperationOutcome.IssueType.DELETED, e.getMessage());
        outcome.getIssueFirstRep().addExtension(EXTENSION_MASTER_ID,
                new Reference("Patient/" + e.getMasterId()));
        return reponse(HttpStatus.GONE, outcome);
    }

    @ExceptionHandler(ParametreFhirInvalideException.class)
    public ResponseEntity<String> parametreInvalide(ParametreFhirInvalideException e) {
        return probleme(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.INVALID, e.getMessage());
    }

    @ExceptionHandler(FormatFhirNonSupporteException.class)
    public ResponseEntity<String> formatNonSupporte(FormatFhirNonSupporteException e) {
        return probleme(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.NOTSUPPORTED,
                e.getMessage());
    }

    /** UUID de chemin mal formé (p.ex. /fhir/R4/Patient/abc) → 400 invalid. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> cheminInvalide(MethodArgumentTypeMismatchException e) {
        return probleme(HttpStatus.BAD_REQUEST, OperationOutcome.IssueType.INVALID,
                "Identifiant de chemin mal formé : " + e.getValue());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> inattendue(Exception e) {
        UUID traceId = UUID.randomUUID();
        LOG.error("Erreur interne façade FHIR traceId={} : {}", traceId, e.getMessage(), e);
        return probleme(HttpStatus.INTERNAL_SERVER_ERROR, OperationOutcome.IssueType.EXCEPTION,
                "Erreur interne — traceId " + traceId);
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private ResponseEntity<String> probleme(HttpStatus statut,
            OperationOutcome.IssueType code, String diagnostics) {
        return reponse(statut, outcome(code, diagnostics));
    }

    private static OperationOutcome outcome(OperationOutcome.IssueType code, String diagnostics) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(code);
        issue.setDiagnostics(diagnostics);
        return outcome;
    }

    private ResponseEntity<String> reponse(HttpStatus statut, OperationOutcome outcome) {
        return ResponseEntity.status(statut)
                .contentType(FhirEncodeur.FHIR_JSON_MEDIA)
                .body(encodeur.json(outcome));
    }
}
