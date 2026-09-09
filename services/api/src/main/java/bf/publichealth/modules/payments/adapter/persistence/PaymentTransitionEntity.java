package bf.publichealth.modules.payments.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import bf.publichealth.modules.payments.domain.PaymentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** La preuve comptable : chaque transition, horodatée, avec l'événement fournisseur. */
@Entity
@Table(name = "payment_transition", schema = "payments")
public class PaymentTransitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, length = 16)
    private PaymentState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 16)
    private PaymentState toState;

    @Column
    private String reason;

    @Column(name = "provider_event_id")
    private String providerEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentTransitionEntity() {
    }

    public PaymentTransitionEntity(UUID paymentId, PaymentState fromState, PaymentState toState,
                                   String reason, String providerEventId) {
        this.paymentId = paymentId;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.providerEventId = providerEventId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public PaymentState getToState() {
        return toState;
    }
}
