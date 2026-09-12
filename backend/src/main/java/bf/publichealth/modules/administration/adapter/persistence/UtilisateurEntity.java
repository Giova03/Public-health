package bf.publichealth.modules.administration.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.StatutUtilisateur;

/**
 * Utilisateur du back-office — miroir applicatif du compte Supabase
 * Auth (migration V12).
 *
 * <p>AUCUN mot de passe, AUCUN hash, AUCUN secret ici : l'authentification
 * vit chez Supabase ; on ne porte que {@code supabase_user_id}, lié UNE
 * fois à l'activation (UNIQUE, définitif). Le compte ne se supprime
 * jamais (garde SQL V12) : la suspension est la seule issue, les
 * transitions de statut sont validées par le domaine
 * ({@link StatutUtilisateur}) AVANT d'atteindre l'entité.</p>
 */
@Entity
@Table(name = "utilisateur", schema = "administration")
public class UtilisateurEntity {

    /** UUID v7 — généré côté serveur (le back-office ne travaille pas hors ligne). */
    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    /** Miroir du compte Supabase Auth — lié une seule fois, à l'activation. */
    @Column(name = "supabase_user_id", unique = true)
    private UUID supabaseUserId;

    @Column(nullable = false)
    private String nom;

    @Column
    private String prenoms;

    /** Référence LOGIQUE organization.structure — SANS FK (loi n°3). */
    @Column(name = "structure_id")
    private UUID structureId;

    @Convert(converter = RoleUtilisateurConverter.class)
    @Column(nullable = false, length = 24)
    private RoleUtilisateur role;

    @Column(name = "mfa_active", nullable = false)
    private boolean mfaActive = false;

    @Convert(converter = StatutUtilisateurConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private StatutUtilisateur statut = StatutUtilisateur.INVITE;

    /** QUI a invité (référence logique administration.utilisateur, nullable). */
    @Column(name = "invited_by")
    private UUID invitedBy;

    /** Hash BCrypt — auth interne V14 (I3). NULL = compte non initialisé. */
    @Column(name = "mot_de_passe_hash")
    private String motDePasseHash;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UtilisateurEntity() {
    }

    public UtilisateurEntity(UUID id, String email, String nom, String prenoms,
                             UUID structureId, RoleUtilisateur role, UUID invitedBy) {
        this.id = id;
        this.email = email;
        this.nom = nom;
        this.prenoms = prenoms;
        this.structureId = structureId;
        this.role = role;
        this.invitedBy = invitedBy;
        this.mfaActive = false;
        this.statut = StatutUtilisateur.INVITE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Activation : lie le compte Supabase (définitif, déjà vérifié
     * UNIQUE par le service) et passe au statut actif. La transition
     * est DÉJÀ validée par le domaine.
     */
    public void activer(UUID supabaseUserId) {
        this.supabaseUserId = supabaseUserId;
        this.statut = StatutUtilisateur.ACTIF;
        this.lastLoginAt = Instant.now();
    }

    /** Suspension (transition déjà validée par le domaine). */
    public void suspendre() {
        this.statut = StatutUtilisateur.SUSPENDU;
    }

    /** Réactivation (transition déjà validée par le domaine). */
    public void reactiver() {
        this.statut = StatutUtilisateur.ACTIF;
    }

    /** Changement de rôle — TRACÉ par le service (from/to en audit). */
    public void changerRole(RoleUtilisateur role) {
        this.role = role;
    }

    /** Bascule MFA — miroir de l'état du compte Supabase Auth. */
    public void basculerMfa(boolean active) {
        this.mfaActive = active;
    }

    /** Définit le hash de mot de passe (auth interne V14 — posé par l'invitation ou le seed démo). */
    public void definirMotDePasse(String hash) {
        this.motDePasseHash = hash;
    }

    /** Consigne la date de dernière connexion (auth interne V14). */
    public void setLastLoginAt(Instant instant) {
        this.lastLoginAt = instant;
    }

    @PreUpdate
    void avantMiseAJour() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public UUID getSupabaseUserId() {
        return supabaseUserId;
    }

    public String getNom() {
        return nom;
    }

    public String getPrenoms() {
        return prenoms;
    }

    public UUID getStructureId() {
        return structureId;
    }

    public RoleUtilisateur getRole() {
        return role;
    }

    public boolean isMfaActive() {
        return mfaActive;
    }

    public StatutUtilisateur getStatut() {
        return statut;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public String getMotDePasseHash() {
        return motDePasseHash;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
