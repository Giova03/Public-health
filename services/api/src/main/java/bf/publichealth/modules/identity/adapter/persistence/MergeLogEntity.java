package bf.publichealth.modules.identity.adapter.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Journal des fusions — la fusion est irréversible, donc invariablement
 * tracée : qui, quand, pourquoi, et l'inventaire exact de ce qui a
 * déménagé (identifiants nationaux, téléphones) du dossier fusionné
 * vers le maître.
 */
@Entity
@Table(name = "merge_log", schema = "identity")
public class MergeLogEntity {

    @Id
    private UUID id;

    @Column(name = "master_id", nullable = false)
    private UUID masterId;

    @Column(name = "merged_id", nullable = false)
    private UUID mergedId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> fields;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @Column(nullable = false)
    private String reason;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt = Instant.now();

    protected MergeLogEntity() {
    }

    public MergeLogEntity(UUID id, UUID masterId, UUID mergedId,
                          Map<String, Object> fields, UUID performedBy, String reason) {
        this.id = id;
        this.masterId = masterId;
        this.mergedId = mergedId;
        this.fields = fields;
        this.performedBy = performedBy;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMasterId() {
        return masterId;
    }

    public UUID getMergedId() {
        return mergedId;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public UUID getPerformedBy() {
        return performedBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }
}
