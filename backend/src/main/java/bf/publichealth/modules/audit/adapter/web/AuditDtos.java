package bf.publichealth.modules.audit.adapter.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import bf.publichealth.modules.audit.domain.AccesUrgence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** DTO du module audit (break-the-glass) — immuables, contrats stables (loi n°5). */
public final class AuditDtos {

    private AuditDtos() {
    }

    // ------------------------------------------------------------------
    // POST /api/v1/audit/break-the-glass
    // ------------------------------------------------------------------

    public record OuvrirAccesUrgenceRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(min = 10, max = 500,
                    message = "La raison de l'accès d'urgence doit faire au moins 10 caractères")
            String reason) {
    }

    /**
     * Réponse de création. {@code userId} vient du contexte sécurité si un JWT
     * est actif ; sinon il est null (documenté : posture Sprint 0 — la brèche
     * est alors portée par l'utilisateur anonyme en base, invisible pour la RLS).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccesUrgenceResponse(UUID id, UUID patientId, UUID userId,
                                       Instant openedAt, Instant expiresAt) {

        public static AccesUrgenceResponse depuis(AccesUrgence acces) {
            return new AccesUrgenceResponse(acces.id(), acces.patientId(),
                    acces.utilisateurPublic(), acces.ouvertA(), acces.expireA());
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v1/audit/emergency-access — lecture (file / attente)
    // ------------------------------------------------------------------

    /** Ligne de lecture : la fenêtre passée apparaît expirée ({@code expire=true}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccesUrgenceDetailResponse(UUID id, UUID patientId, UUID userId, String reason,
                                             Instant openedAt, Instant expiresAt, boolean expire,
                                             boolean reviewed, String reviewComment,
                                             Instant reviewedAt) {

        public static AccesUrgenceDetailResponse depuis(AccesUrgence acces) {
            return new AccesUrgenceDetailResponse(acces.id(), acces.patientId(),
                    acces.utilisateurPublic(), acces.raison(), acces.ouvertA(), acces.expireA(),
                    acces.expire(), acces.revu(), acces.commentaireRevue(), acces.revuA());
        }
    }

    // ------------------------------------------------------------------
    // POST /api/v1/audit/emergency-access/{id}/review
    // ------------------------------------------------------------------

    public record RevoirAccesUrgenceRequest(
            @NotBlank @Size(max = 500, message = "Le commentaire de revue fait au plus 500 caractères")
            String comment) {
    }
}
