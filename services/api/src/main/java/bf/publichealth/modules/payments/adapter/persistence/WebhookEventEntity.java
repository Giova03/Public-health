package bf.publichealth.modules.payments.adapter.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Événement webhook consommé — l'idempotence est LA :
 * un eventId fournisseur ne traverse le domaine qu'une seule fois.
 * Un rejeu réseau est reconnu et acquitté, jamais réappliqué.
 */
@Entity
@Table(name = "webhook_event", schema = "payments")
public class WebhookEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String type;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "signature_ok", nullable = false)
    private boolean signatureOk;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEventEntity() {
    }

    public WebhookEventEntity(String eventId, String type, String payloadHash,
                              boolean signatureOk, String status) {
        this.eventId = eventId;
        this.type = type;
        this.payloadHash = payloadHash;
        this.signatureOk = signatureOk;
        this.status = status;
        this.receivedAt = Instant.now();
    }

    public String getStatus() {
        return status;
    }
}
