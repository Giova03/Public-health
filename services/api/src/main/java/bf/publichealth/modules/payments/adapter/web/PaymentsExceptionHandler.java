package bf.publichealth.modules.payments.adapter.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bf.publichealth.modules.payments.domain.IllegalInvoiceTransitionException;
import bf.publichealth.modules.payments.domain.InvoiceIntrouvableException;

/**
 * Erreurs de facturation/réconciliation au format problem+json (RFC 7807).
 *
 * <p>Conseil LOCAL au module payments : il ne mappe QUE les exceptions
 * de facturation, et uniquement pour les contrôleurs de ce module
 * (basePackages) — le GlobalExceptionHandler commun reste seul juge du
 * reste (les IllegalPaymentTransitionException / signatures webhook y
 * vivent déjà). @Order(0) le fait évaluer AVANT le conseil commun :
 * sans cela, son fallback Exception.class transformerait nos 404/409
 * métier en 500.</p>
 */
@RestControllerAdvice(basePackages = "bf.publichealth.modules.payments.adapter.web")
@Order(0)
public class PaymentsExceptionHandler {

    @ExceptionHandler(InvoiceIntrouvableException.class)
    public ResponseEntity<ProblemDetail> factureIntrouvable(InvoiceIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Facture introuvable");
        problem.setProperty("invoiceId", e.getInvoiceId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalInvoiceTransitionException.class)
    public ResponseEntity<ProblemDetail> transitionIllegale(IllegalInvoiceTransitionException e) {
        // Ré-annulation, émission d'une facture émise, annulation d'une facture
        // encaissée : 409, l'historique est intact.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Transition de facture illégale");
        problem.setProperty("from", e.getFrom().getCode());
        problem.setProperty("to", e.getTo().getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException e) {
        // Garde de domaine (facture sans ligne, motif absent, limit invalide) :
        // le domaine est pur, il parle IllegalArgumentException — 400 propre.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
