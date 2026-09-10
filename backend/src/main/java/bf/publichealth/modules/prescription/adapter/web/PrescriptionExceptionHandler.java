package bf.publichealth.modules.prescription.adapter.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bf.publichealth.modules.prescription.domain.IllegalPrescriptionTransitionException;
import bf.publichealth.modules.prescription.domain.PatientIntrouvableException;
import bf.publichealth.modules.prescription.domain.PrescriptionInactiveException;
import bf.publichealth.modules.prescription.domain.PrescriptionIntrouvableException;
import bf.publichealth.modules.prescription.domain.QuantityExceededException;

/**
 * Erreurs du module prescription au format problem+json (RFC 7807).
 *
 * <p>Conseil LOCAL au module : il ne mappe QUE les exceptions du domaine
 * prescription, et uniquement pour les contrôleurs de ce module
 * (basePackages) — le GlobalExceptionHandler commun reste seul juge du
 * reste. @Order(0) le fait évaluer AVANT le conseil commun : sans cela,
 * son fallback Exception.class transformerait nos 404/409 métier en 500.</p>
 */
@RestControllerAdvice(basePackages = "bf.publichealth.modules.prescription")
@Order(0)
public class PrescriptionExceptionHandler {

    @ExceptionHandler(PatientIntrouvableException.class)
    public ResponseEntity<ProblemDetail> patientIntrouvable(PatientIntrouvableException e) {
        // Sans patient actif (module identity), rien ne se crée.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Patient introuvable");
        problem.setProperty("patientId", e.getPatientId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(PrescriptionIntrouvableException.class)
    public ResponseEntity<ProblemDetail> prescriptionIntrouvable(
            PrescriptionIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Prescription introuvable");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(PrescriptionInactiveException.class)
    public ResponseEntity<ProblemDetail> prescriptionInactive(PrescriptionInactiveException e) {
        // Annulée ou en erreur : la dispensation est refusée, l'historique intact.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Prescription inactive");
        problem.setProperty("statut", e.getStatut().getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(QuantityExceededException.class)
    public ResponseEntity<ProblemDetail> quantiteNonDispensable(QuantityExceededException e) {
        // 409 = contrat comptoir : le restant exact et le demandé voyagent
        // dans la réponse, l'agent corrige sa saisie sans deviner.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Quantité non dispensable");
        problem.setProperty("restant", e.getRestant());
        problem.setProperty("demande", e.getDemandee());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalPrescriptionTransitionException.class)
    public ResponseEntity<ProblemDetail> transitionIllegale(
            IllegalPrescriptionTransitionException e) {
        // Ré-annulation, contre-entrée sur un statut terminal : 409.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Transition de prescription illégale");
        problem.setProperty("from", e.getFrom().getCode());
        problem.setProperty("to", e.getTo().getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException e) {
        // Garde de domaine (quantité non positive, prescription sans ligne) :
        // le domaine est pur, il parle IllegalArgumentException — 400 propre.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
