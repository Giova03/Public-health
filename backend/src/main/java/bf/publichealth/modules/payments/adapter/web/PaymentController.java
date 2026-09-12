package bf.publichealth.modules.payments.adapter.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.payments.adapter.persistence.PaymentEntity;
import bf.publichealth.modules.payments.application.PaymentService;
import jakarta.validation.Valid;

/**
 * API paiements — /api/v1/payments.
 * Idempotency-Key OBLIGATOIRE sur toute création (contrat d'API, pas option).
 * Un rejeu renvoie 200 avec le paiement existant, jamais un doublon.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentDtos.PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentDtos.CreatePaymentRequest request) {

        var creation = paymentService.create(new PaymentService.CreatePaymentCommand(
                idempotencyKey, request.invoiceId(), request.amount(),
                request.providerRef(), request.initiatedBy()));

        return ResponseEntity
                .status(creation.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PaymentDtos.PaymentResponse.from(creation.payment()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentDtos.PaymentResponse> get(@PathVariable UUID id) {
        PaymentEntity payment = paymentService.find(id);
        if (payment == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, "Paiement introuvable : " + id);
            return ResponseEntity.of(problem).build();
        }
        return ResponseEntity.ok(PaymentDtos.PaymentResponse.from(payment));
    }
}
