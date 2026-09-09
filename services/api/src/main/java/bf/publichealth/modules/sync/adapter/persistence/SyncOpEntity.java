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
 * Journal d'idempotence de l'uplink — UN opId ne s'applique qu'une fois,
 * quoi qu'il arrive (V4). Le résultat stocké (result + result_detail)
 * EST la réponse : rejouer un lot entier redonne exactement les mêmes
 * résultats, sans ré-appliquer quoi que ce soit.
 */
@Entity
@Table(name = "op", schema = "sync")
public class SyncOpEntity {

    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String CONFLICT = "CONFLICT";

    @Id
    @Column(name = "op_id")
    private UUID opId;

    /** NULL accepté depuis V7 (lot sans utilisateur identifié). */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, length = 48)
    private String entity;

    /** NULL accepté depuis V7 (CONFLIT patient : aucune entité produite). */
    @Column(name = "entity_id")
    private UUID entityId;

    @Column(nullable = false, length = 16)
    private String result;

    /** Résultat détaillé (candidates d'un CONFLICT, motif d'un REJECTED…). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_detail", columnDefinition = "jsonb")
    private String resultDetail;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SyncOpEntity() {
    }

    public SyncOpEntity(UUID opId, UUID userId, String entity, UUID entityId,
                        String result, String resultDetail) {
        this.opId = opId;
        this.userId = userId;
        this.entity = entity;
        this.entityId = entityId;
        this.result = result;
        this.resultDetail = resultDetail;
        this.receivedAt = Instant.now();
    }

    public UUID getOpId() {
        return opId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEntity() {
        return entity;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getResult() {
        return result;
    }

    public String getResultDetail() {
        return resultDetail;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
