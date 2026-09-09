package bf.publichealth.modules.audit.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registre des accès d'urgence (break-the-glass) — table
 * audit.emergency_access (V10). L'accès d'urgence n'est jamais bloquant :
 * il est tracé et revu a posteriori. La fenêtre (opened_at → expires_at)
 * fait 30 minutes.
 *
 * <p>user_id NOT NULL : sans contexte sécurité (posture Sprint 0), le
 * service inscrit l'utilisateur ANONYME — voir
 * {@code AccesUrgence.UTILISATEUR_ANONYME}.</p>
 */
@Entity
@Table(name = "emergency_access", schema = "audit")
public class AccesUrgenceEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID utilisateurId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean reviewed;

    @Column(name = "review_comment")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected AccesUrgenceEntity() {
    }

    public AccesUrgenceEntity(UUID id, UUID utilisateurId, UUID patientId, String reason,
                              Instant openedAt, Instant expiresAt) {
        this.id = id;
        this.utilisateurId = utilisateurId;
        this.patientId = patientId;
        this.reason = reason;
        this.openedAt = openedAt;
        this.expiresAt = expiresAt;
        this.reviewed = false;
    }

    /** Examen a posteriori : marque la revue, horodate, conserve le commentaire. */
    public void marquerRevu(String commentaire, Instant revuA) {
        this.reviewed = true;
        this.reviewComment = commentaire;
        this.reviewedAt = revuA;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUtilisateurId() {
        return utilisateurId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isReviewed() {
        return reviewed;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
