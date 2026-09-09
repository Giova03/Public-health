package bf.publichealth.modules.payments.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import bf.publichealth.modules.payments.domain.PaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Paiement — montant en XOF (pas de centimes, numeric(12,0)).
 * L'état courant est une commodité de lecture ; la PREUVE est
 * l'historique des transitions (payment_transition), jamais altéré.
 */
@Entity
@Table(name = "payment", schema = "payments")
public class PaymentEntity {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "XOF";

    @Column(nullable = false, length = 32)
    private String provider = "FEDAPAY";

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentState state = PaymentState.INITIATED;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {
    }

    public PaymentEntity(UUID id, UUID invoiceId, BigDecimal amount, String providerRef,
                         String idempotencyKey, UUID initiatedBy) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.providerRef = providerRef;
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Applique une transition DÉJÀ validée par le domaine (requireTransitionTo). */
    public void applyTransition(PaymentState target) {
        this.state = target;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PaymentState getState() {
        return state;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
