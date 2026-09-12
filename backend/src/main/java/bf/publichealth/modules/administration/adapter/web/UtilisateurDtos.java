package bf.publichealth.modules.administration.adapter.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bf.publichealth.modules.administration.adapter.persistence.UtilisateurEntity;

/** DTO du module administration — immuables, contrats stables (loi n°5). */
public final class UtilisateurDtos {

    private UtilisateurDtos() {
    }

    /** Codes de rôles (regexp unique, partagée par toutes les requêtes). */
    static final String ROLES = "admin|medecin|infirmier|pharmacien|agent_financier|superviseur";

    // ------------------------------------------------------------------
    // Requêtes
    // ------------------------------------------------------------------

    public record InviterUtilisateurRequest(
            @NotBlank(message = "L'email est OBLIGATOIRE")
            @Email(message = "L'email doit être une adresse valide")
            @Size(max = 255) String email,
            @NotBlank(message = "Le rôle est OBLIGATOIRE")
            @Pattern(regexp = ROLES, message = "role doit valoir " + ROLES)
            String role,
            @NotBlank(message = "Le nom est OBLIGATOIRE")
            @Size(max = 100) String nom,
            @Size(max = 100) String prenoms,
            UUID structureId,
            UUID invitedBy) {
    }

    /** Activation : la liaison définitive du compte Supabase Auth. */
    public record ActiverUtilisateurRequest(
            @NotNull(message = "supabaseUserId est OBLIGATOIRE (liaison du compte Supabase Auth)")
            UUID supabaseUserId) {
    }

    /** Suspension : le motif est OBLIGATOIRE (dimension POURQUOI de l'audit). */
    public record SuspendreUtilisateurRequest(
            @NotBlank(message = "Le motif de suspension est OBLIGATOIRE")
            @Size(min = 10, max = 500,
                  message = "Le motif de suspension porte au moins 10 caractères (tracé en audit)")
            String reason) {
    }

    public record ChangerRoleRequest(
            @NotBlank(message = "Le nouveau rôle est OBLIGATOIRE")
            @Pattern(regexp = ROLES, message = "role doit valoir " + ROLES)
            String role) {
    }

    public record BasculerMfaRequest(
            @NotNull(message = "active est OBLIGATOIRE (true/false)")
            Boolean active) {
    }

    // ------------------------------------------------------------------
    // Réponses
    // ------------------------------------------------------------------

    public record UtilisateurResponse(
            UUID id, String email, UUID supabaseUserId, String nom, String prenoms,
            UUID structureId, String role, boolean mfaActive, String status,
            UUID invitedBy, Instant lastLoginAt, Instant createdAt, Instant updatedAt) {

        public static UtilisateurResponse from(UtilisateurEntity u) {
            return new UtilisateurResponse(u.getId(), u.getEmail(), u.getSupabaseUserId(),
                    u.getNom(), u.getPrenoms(), u.getStructureId(), u.getRole().getCode(),
                    u.isMfaActive(), u.getStatut().getCode(), u.getInvitedBy(),
                    u.getLastLoginAt(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }
}
