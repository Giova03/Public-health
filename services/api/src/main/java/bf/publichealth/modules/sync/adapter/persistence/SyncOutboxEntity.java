package bf.publichealth.modules.sync.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outbox transactionnel (V4) : l'événement est écrit dans la MÊME
 * transaction que le fait métier — la porte HUB (Kafka, épique
 * ultérieure) publiera ensuite sans risque de divergence.
 */
@Entity
@Table(name = "outbox", schema = "sync")
public class SyncOutboxEntity {

    @Id
    private UUID eventId;

    /** Ex : sync.patient.created, sync.encounter.created. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private boolean published = false;

    protected SyncOutboxEntity() {
    }

    public SyncOutboxEntity(UUID eventId, String eventType, UUID aggregateId,
                            String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public boolean isPublished() {
        return published;
    }
}
