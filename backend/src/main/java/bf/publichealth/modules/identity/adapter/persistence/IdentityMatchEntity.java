package bf.publichealth.modules.identity.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Rapprochement en attente de revue humaine (zone grise du MPI).
 * ACCEPTED a déclenché la fusion ; REJECTED l'a écartée — dans les deux
 * cas, la décision et son motif restent attachés au rapprochement.
 */
@Entity
@Table(name = "identity_match", schema = "identity")
public class IdentityMatchEntity {

    @Id
    private UUID id;

    @Column(name = "candidate_a", nullable = false)
    private UUID candidateA;

    @Column(name = "candidate_b", nullable = false)
    private UUID candidateB;

    @Column(nullable = false)
    private double score;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    private String motif;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected IdentityMatchEntity() {
    }

    public IdentityMatchEntity(UUID id, UUID candidateA, UUID candidateB, double score,
                               String method, String status, UUID reviewedBy,
                               Instant reviewedAt, String motif) {
        this.id = id;
        this.candidateA = candidateA;
        this.candidateB = candidateB;
        this.score = score;
        this.method = method;
        this.status = status;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.motif = motif;
    }

    /** Statue le rapprochement — ne s'appelle qu'une fois (PENDING → *). */
    public void statuer(String decision, UUID par, Instant quand, String pourquoi) {
        this.status = decision;
        this.reviewedBy = par;
        this.reviewedAt = quand;
        this.motif = pourquoi;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCandidateA() {
        return candidateA;
    }

    public UUID getCandidateB() {
        return candidateB;
    }

    public double getScore() {
        return score;
    }

    public String getMethod() {
        return method;
    }

    public String getStatus() {
        return status;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getMotif() {
        return motif;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
