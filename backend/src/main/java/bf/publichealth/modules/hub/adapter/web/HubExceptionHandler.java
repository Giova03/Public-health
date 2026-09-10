package bf.publichealth.modules.hub.adapter.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.hub.domain.DestinationDejaConnueException;
import bf.publichealth.modules.hub.domain.DestinationInconnueException;
import bf.publichealth.modules.hub.domain.MessageHubIntrouvableException;
import bf.publichealth.modules.hub.domain.MessageNonMortException;

/**
 * Erreurs du module hub au format problem+json (RFC 7807).
 *
 * <p>Conseil LOCAL au module hub (patron PaymentsExceptionHandler) : il
 * ne mappe QUE les exceptions HUB, et uniquement pour les contrôleurs
 * de ce module (basePackages) — le GlobalExceptionHandler commun reste
 * seul juge du reste. @Order(0) le fait évaluer AVANT le conseil commun
 * : sans cela, son fallback Exception.class transformerait nos
 * 404/409/400 métier en 500.</p>
 */
@RestControllerAdvice(basePackages = "bf.publichealth.modules.hub.adapter.web")
@Order(0)
public class HubExceptionHandler {

    @ExceptionHandler(MessageHubIntrouvableException.class)
    public ResponseEntity<ProblemDetail> messageIntrouvable(MessageHubIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Message HUB introuvable");
        problem.setProperty("messageId", e.getMessageId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MessageNonMortException.class)
    public ResponseEntity<ProblemDetail> messageNonMort(MessageNonMortException e) {
        // La relance manuelle est réservée à la lettre morte : l'état d'un
        // message vivant n'est pas réinscriptible à la main.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Message non mort");
        problem.setProperty("status", e.getStatut());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DestinationDejaConnueException.class)
    public ResponseEntity<ProblemDetail> destinationDejaConnue(DestinationDejaConnueException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Destination déjà déclarée");
        problem.setProperty("code", e.getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(DestinationInconnueException.class)
    public ResponseEntity<ProblemDetail> destinationInconnue(DestinationInconnueException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage());
        problem.setTitle("Destination inconnue");
        problem.setProperty("code", e.getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException e) {
        // Garde de domaine (statut inconnu, limit hors bornes, code mal
        // formé) : le domaine est pur, il parle IllegalArgumentException.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage());
        problem.setTitle("Requête HUB invalide");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> chargeIllisible(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Charge JSON illisible : " + e.getMostSpecificCause().getMessage());
        problem.setTitle("Charge invalide");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> parametreIllisible(MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Paramètre invalide : " + e.getName());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
