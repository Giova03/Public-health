package bf.publichealth.modules.payments.adapter.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.payments.application.PaymentService;

/**
 * Webhook FedaPay — /api/v1/webhooks/fedapay.
 * La charge brute est lue TELLE QUELLE (la signature porte dessus).
 * Le webhook accélère ; la réconciliation nocturne fait foi (ADR-006).
 */
@RestController
public class FedapayWebhookController {

    private final PaymentService paymentService;
    private final String eventIdHeader;

    public FedapayWebhookController(
            PaymentService paymentService,
            @Value("${fedapay.event-id-header:X-Event-Id}") String eventIdHeader) {
        this.paymentService = paymentService;
        this.eventIdHeader = eventIdHeader;
    }

    @PostMapping(value = "/api/v1/webhooks/fedapay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PaymentDtos.WebhookAckResponse handle(
            @RequestHeader(value = "${fedapay.event-id-header:X-Event-Id}") String eventId,
            @RequestHeader(value = "${fedapay.webhook-signature-header:X-FedaPay-Signature}",
                    required = false) String signature,
            @RequestBody String rawBody) {

        var ack = paymentService.applyWebhook(eventId, rawBody, signature);
        return new PaymentDtos.WebhookAckResponse(ack.status(), ack.message());
    }
}
