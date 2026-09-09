package bf.publichealth.modules.payments.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Écart durable de réconciliation : rétrogradation ignorée (forward-only),
 * état prestataire inconnu, montant divergent, webhook orphelin
 * (run_id NULL : constaté à la réception du webhook, résolu par un run
 * ultérieur quand le paiement apparaît).
 */
@Entity
@Table(name = "reconciliation_discrepancy", schema = "payments")
public class ReconciliationDiscrepancyEntity {

    @Id
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column
    private String reference;

    /** RETROGRADATION_IGNOREE | ETAT_INCONNU | MONTANT_DIVERGE | WEBHOOK_ORPHELIN. */
    @Column(nullable = false, length = 32)
    private String kind;

    @Column
    private String description;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationDiscrepancyEntity() {
    }

    public ReconciliationDiscrepancyEntity(UUID id, UUID runId, UUID paymentId, String reference,
                                           String kind, String description) {
        this.id = id;
        this.runId = runId;
        this.paymentId = paymentId;
        this.reference = reference;
        this.kind = kind;
        this.description = description;
        this.createdAt = Instant.now();
    }

    /** L'orphelin est résolu : le paiement est connu et traité par le run. */
    public void resoudre(UUID runId) {
        this.runId = runId;
        this.resolved = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getReference() {
        return reference;
    }

    public String getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public boolean isResolved() {
        return resolved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
