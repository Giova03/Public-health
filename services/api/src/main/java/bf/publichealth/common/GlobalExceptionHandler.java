package bf.publichealth.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bf.publichealth.modules.payments.domain.IllegalPaymentTransitionException;
import bf.publichealth.modules.payments.domain.WebhookSignatureInvalidException;

/**
 * Erreurs au format problem+json (RFC 7807) : type, title, status, detail.
 * Aucune pile interne ne fuit — le traceId est porté par les logs structurés.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalPaymentTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalPaymentTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Transition de paiement illégale");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(WebhookSignatureInvalidException.class)
    public ResponseEntity<ProblemDetail> webhookSignature(WebhookSignatureInvalidException e) {
        // 401 générique : on ne donne rien d'exploitable à un falsificateur.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Webhook rejeté");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "En-tête obligatoire manquant : " + e.getHeaderName());
        problem.setTitle("Requête incomplète");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Charge invalide : " + e.getMessage());
        problem.setTitle("Validation en échec");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        UUID traceId = UUID.randomUUID();
        LOG.error("Erreur interne traceId={} : {}", traceId, e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Erreur interne — traceId " + traceId);
        problem.setTitle("Erreur interne");
        return ResponseEntity.of(problem).build();
    }
}
