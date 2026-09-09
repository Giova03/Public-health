package bf.publichealth.modules.audit.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrée du journal d'audit — six dimensions (WHO/WHAT/WHEN/WHERE/WHY/RESULT),
 * append-only, chaînée par hachage : chaque entrée scelle la précédente.
 * La table refuse UPDATE et DELETE au niveau SQL (migration V5).
 */
@Entity
@Table(name = "entry", schema = "audit")
public class AuditEntryEntity {

    public enum Result {
        SUCCESS, FAILURE, DENIED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;                    // QUI

    @Column(nullable = false)
    private String action;                   // QUOI (ex : PAYMENT_TRANSITION)

    @Column(nullable = false)
    private String entity;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "facility_id")
    private UUID facilityId;                 // OÙ

    @Column
    private String reason;                   // POURQUOI (obligatoire pour break-the-glass)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Result result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;                  // complément de RESULTAT (JSON)

    @Column(name = "prev_hash")
    private String previousHash;

    @Column(nullable = false)
    private String hash;

    protected AuditEntryEntity() {
    }

    public AuditEntryEntity(Instant occurredAt, UUID actorId, String action, String entity,
                            UUID entityId, UUID facilityId, String reason, Result result,
                            String details, String previousHash, String hash) {
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.facilityId = facilityId;
        this.reason = reason;
        this.result = result;
        this.details = details;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    public Long getId() {
        return id;
    }

    public String getHash() {
        return hash;
    }
}
