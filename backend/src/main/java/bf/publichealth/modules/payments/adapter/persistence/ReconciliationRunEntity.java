package bf.publichealth.modules.payments.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un passage de la réconciliation — le rapport comptable qui FAIT FOI
 * (ADR-006). counts/detail sont sérialisés en jsonb par le service
 * (patron AuditEntryEntity.details).
 */
@Entity
@Table(name = "reconciliation_run", schema = "payments")
public class ReconciliationRunEntity {

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** JSON : {examined, confirmed, advanced, failed, ignored, orphans_resolved}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String counts;

    /** 'schedule' (job nocturne) | 'manuel' (POST /api/v1/reconciliation/run). */
    @Column(name = "triggered_by", nullable = false, length = 24)
    private String triggeredBy;

    /** JSON libre : comptes internes (états inconnus, orphelins restants...). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    protected ReconciliationRunEntity() {
    }

    public ReconciliationRunEntity(UUID id, Instant startedAt, String triggeredBy) {
        this.id = id;
        this.startedAt = startedAt;
        this.triggeredBy = triggeredBy;
    }

    /** Clôture du run : compteurs + horodatage de fin. */
    public void terminer(String counts, String detail, Instant finishedAt) {
        this.counts = counts;
        this.detail = detail;
        this.finishedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public String getCounts() {
        return counts;
    }

    public String getDetail() {
        return detail;
    }
}
