package bf.publichealth.modules.administration.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.administration.adapter.persistence.UtilisateurEntity;
import bf.publichealth.modules.administration.adapter.persistence.UtilisateurRepository;
import bf.publichealth.modules.administration.domain.CompteDejaLierException;
import bf.publichealth.modules.administration.domain.EmailDejaUtiliseException;
import bf.publichealth.modules.administration.domain.IllegalUtilisateurTransitionException;
import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.StatutUtilisateur;
import bf.publichealth.modules.administration.domain.StructureInconnueException;
import bf.publichealth.modules.administration.domain.UtilisateurIntrouvableException;

/**
 * Cas d'usage utilisateurs du back-office — épique E6.
 *
 * <p>L'invitation (email + rôle + structure) crée un compte
 * {@code invite} : AUCUN mot de passe ne transite par ce module —
 * l'authentification vit chez Supabase Auth, et l'activation LIE le
 * miroir {@code supabase_user_id} une fois pour toutes (UNIQUE, 409
 * si déjà lié, y compris à un autre utilisateur). La suspension
 * (motif OBLIGATOIRE) est la seule issue d'un compte : on ne DELETE
 * jamais (garde SQL V12) — l'administratif est append-only.</p>
 *
 * <p>TOUTES les transitions écrivent dans l'audit (patron payments :
 * six dimensions, chaînage, REQUIRES_NEW dans l'AuditRecorder — les
 * refus DENIED survivent au rollback de la transaction métier).
 * L'acteur vient du contexte sécurité (JWT actif) ou de l'anonyme
 * sentinel 000…0 en posture Sprint 0 (patron V10 break-the-glass).</p>
 */
@Service
public class UtilisateurService {

    private static final Logger LOG = LoggerFactory.getLogger(UtilisateurService.class);

    /** L'utilisateur anonyme — posture Sprint 0 sans JWT (patron V10). */
    public static final UUID UTILISATEUR_ANONYME =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    // ------------------------------------------------------------------
    // Entrées / sorties (contrats stables, loi n°5 des modules)
    // ------------------------------------------------------------------

    public record CommandeInvitation(String email, RoleUtilisateur role, String nom,
                                     String prenoms, UUID structureId, UUID invitedBy) {
    }

    private final UtilisateurRepository utilisateurRepository;
    private final StructureLookup structureLookup;
    private final AuditRecorder auditRecorder;

    public UtilisateurService(UtilisateurRepository utilisateurRepository,
                              StructureLookup structureLookup, AuditRecorder auditRecorder) {
        this.utilisateurRepository = utilisateurRepository;
        this.structureLookup = structureLookup;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Invitation — idempotence PAR EMAIL : un 409 clair, jamais deux comptes
    // ------------------------------------------------------------------

    /**
     * Inviter : crée le compte en statut {@code invite}, sans mot de
     * passe (l'email d'invitation partira via Supabase Auth). Un email
     * déjà connu — quelle que soit la casse — refuse en 409 portant le
     * statut existant : ré-inviter un SUSPENDU ne le réinvite pas par
     * erreur (il faut le réactiver à des fins délibérées).
     */
    @Transactional
    public UtilisateurEntity inviter(CommandeInvitation commande, UUID acteur) {
        String email = normaliserEmail(commande.email());
        RoleUtilisateur role = commande.role();
        UUID invitedBy = commande.invitedBy() != null ? commande.invitedBy() : acteurOrSentinelle(acteur);

        // Loi n°3 : structure_id sans FK — l'existence est portée par le
        // port vers organization. Sans structure connue, rien.
        if (commande.structureId() != null && !structureLookup.structureConnue(commande.structureId())) {
            audit(invitedBy, "UTILISATEUR_INVITATION_REFUSED", "utilisateur", null, null,
                    "STRUCTURE_INCONNUE", AuditEntryEntity.Result.DENIED,
                    Map.of("email", email, "structureId", commande.structureId().toString()));
            throw new StructureInconnueException(commande.structureId());
        }

        // Idempotence par email : la norme d'identité est l'adresse,
        // jamais le UUID d'invitation — le rejeu est refusé explicitement.
        utilisateurRepository.findByEmailIgnoreCase(email).ifPresent(existant -> {
            audit(invitedBy, "UTILISATEUR_INVITATION_REFUSED", "utilisateur",
                    existant.getId(), null, "EMAIL_DEJA_UTILISE", AuditEntryEntity.Result.DENIED,
                    Map.of("email", email, "statutExistant", existant.getStatut().getCode()));
            throw new EmailDejaUtiliseException(email, existant.getStatut().getCode());
        });

        UtilisateurEntity utilisateur = utilisateurRepository.save(new UtilisateurEntity(
                UuidV7.next(), email, commande.nom().trim(), blanchi(commande.prenoms()),
                commande.structureId(), role, invitedBy));

        audit(invitedBy, "UTILISATEUR_INVITE", "utilisateur", utilisateur.getId(),
                utilisateur.getStructureId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("email", email, "role", role.getCode()));

        LOG.info("Utilisateur invité email={} role={} par={}", email, role.getCode(), invitedBy);
        return utilisateur;
    }

    // ------------------------------------------------------------------
    // Activation — liaison définitive du compte Supabase
    // ------------------------------------------------------------------

    /**
     * Active le compte une fois le compte Supabase créé : renseigne
     * {@code supabase_user_id} (définitif — UNIQUE) et passe au statut
     * {@code actif}. 409 si le compte Supabase est déjà lié (à cet
     * utilisateur ou à un autre), 409 si le compte n'est pas INVITE.
     */
    @Transactional
    public UtilisateurEntity activer(UUID utilisateurId, UUID supabaseUserId, UUID acteur) {
        if (supabaseUserId == null) {
            throw new IllegalArgumentException(
                    "supabaseUserId est OBLIGATOIRE : c'est la liaison du compte Supabase Auth");
        }
        UUID auteur = acteurOrSentinelle(acteur);
        UtilisateurEntity utilisateur = charger(utilisateurId);

        // La liaison est définitive : un compte Supabase appartient à un
        // et un seul utilisateur du back-office (UNIQUE V12 + garde
        // applicative au message plus clair que l'index SQL brut).
        utilisateurRepository.findBySupabaseUserId(supabaseUserId).ifPresent(porteur -> {
            audit(auteur, "UTILISATEUR_ACTIVATION_REFUSED", "utilisateur", utilisateurId, null,
                    "COMPTE_SUPABASE_DEJA_LIE", AuditEntryEntity.Result.DENIED,
                    Map.of("supabaseUserId", supabaseUserId.toString(),
                            "utilisateurPorteur", porteur.getId().toString()));
            throw new CompteDejaLierException(supabaseUserId, porteur.getId(), porteur.getEmail());
        });

        try {
            utilisateur.getStatut().exigerTransitionVers(StatutUtilisateur.ACTIF);
        } catch (IllegalUtilisateurTransitionException e) {
            audit(auteur, "UTILISATEUR_ACTIVATION_REFUSED", "utilisateur", utilisateurId,
                    utilisateur.getStructureId(), "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", e.getFrom().getCode(), "to", e.getTo().getCode()));
            throw e;
        }

        utilisateur.activer(supabaseUserId);
        utilisateurRepository.save(utilisateur);

        audit(auteur, "UTILISATEUR_ACTIVATED", "utilisateur", utilisateur.getId(),
                utilisateur.getStructureId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("supabaseUserId", supabaseUserId.toString(),
                        "email", utilisateur.getEmail()));

        LOG.info("Utilisateur activé id={} supabaseUserId={}", utilisateurId, supabaseUserId);
        return utilisateur;
    }

    // ------------------------------------------------------------------
    // Suspension / réactivation — motif OBLIGATOIRE, jamais de DELETE
    // ------------------------------------------------------------------

    @Transactional
    public UtilisateurEntity suspendre(UUID utilisateurId, String raison, UUID acteur) {
        exigerRaison(raison);
        UUID auteur = acteurOrSentinelle(acteur);
        UtilisateurEntity utilisateur = charger(utilisateurId);

        try {
            utilisateur.getStatut().exigerTransitionVers(StatutUtilisateur.SUSPENDU);
        } catch (IllegalUtilisateurTransitionException e) {
            audit(auteur, "UTILISATEUR_SUSPENSION_REFUSED", "utilisateur", utilisateurId,
                    utilisateur.getStructureId(), "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", e.getFrom().getCode(), "to", e.getTo().getCode()));
            throw e;
        }

        utilisateur.suspendre();
        utilisateurRepository.save(utilisateur);

        // La RAISON voyage dans la dimension POURQUOI de l'audit — c'est
        // la trace réglementaire de la suspension.
        audit(auteur, "UTILISATEUR_SUSPENDED", "utilisateur", utilisateur.getId(),
                utilisateur.getStructureId(), raison, AuditEntryEntity.Result.SUCCESS,
                Map.of("from", StatutUtilisateur.ACTIF.getCode(),
                        "to", StatutUtilisateur.SUSPENDU.getCode()));

        LOG.warn("Utilisateur suspendu id={} raison={}", utilisateurId, raison);
        return utilisateur;
    }

    @Transactional
    public UtilisateurEntity reactiver(UUID utilisateurId, UUID acteur) {
        UUID auteur = acteurOrSentinelle(acteur);
        UtilisateurEntity utilisateur = charger(utilisateurId);

        try {
            utilisateur.getStatut().exigerTransitionVers(StatutUtilisateur.ACTIF);
        } catch (IllegalUtilisateurTransitionException e) {
            audit(auteur, "UTILISATEUR_REACTIVATION_REFUSED", "utilisateur", utilisateurId,
                    utilisateur.getStructureId(), "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", e.getFrom().getCode(), "to", e.getTo().getCode()));
            throw e;
        }

        utilisateur.reactiver();
        utilisateurRepository.save(utilisateur);

        audit(auteur, "UTILISATEUR_REACTIVATED", "utilisateur", utilisateur.getId(),
                utilisateur.getStructureId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("from", StatutUtilisateur.SUSPENDU.getCode(),
                        "to", StatutUtilisateur.ACTIF.getCode()));
        return utilisateur;
    }

    // ------------------------------------------------------------------
    // Changement de rôle / MFA — tracés
    // ------------------------------------------------------------------

    /** Changement de rôle : TRACÉ from → to (audit), depuis n'importe quel statut. */
    @Transactional
    public UtilisateurEntity changerRole(UUID utilisateurId, RoleUtilisateur nouveauRole, UUID acteur) {
        UUID auteur = acteurOrSentinelle(acteur);
        UtilisateurEntity utilisateur = charger(utilisateurId);
        RoleUtilisateur ancienRole = utilisateur.getRole();

        utilisateur.changerRole(nouveauRole);
        utilisateurRepository.save(utilisateur);

        audit(auteur, "UTILISATEUR_ROLE_CHANGED", "utilisateur", utilisateur.getId(),
                utilisateur.getStructureId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("from", ancienRole.getCode(), "to", nouveauRole.getCode()));

        LOG.info("Rôle changé id={} {} → {}", utilisateurId, ancienRole.getCode(),
                nouveauRole.getCode());
        return utilisateur;
    }

    /**
     * Bascule MFA : miroir de l'état du compte Supabase Auth, TRACÉ.
     * Autorisé quel que soit le statut — sur un INVITE, le drapeau
     * prendra son sens à l'activation (documenté : le compte Supabase
     * n'existe pas encore, la valeur est une anticipation administrative).
     */
    @Transactional
    public UtilisateurEntity basculerMfa(UUID utilisateurId, boolean active, UUID acteur) {
        UUID auteur = acteurOrSentinelle(acteur);
        UtilisateurEntity utilisateur = charger(utilisateurId);

        utilisateur.basculerMfa(active);
        utilisateurRepository.save(utilisateur);

        audit(auteur, active ? "UTILISATEUR_MFA_ENABLED" : "UTILISATEUR_MFA_DISABLED",
                "utilisateur", utilisateur.getId(), utilisateur.getStructureId(), null,
                AuditEntryEntity.Result.SUCCESS, Map.of("mfaActive", active));

        LOG.info("MFA {} id={}", active ? "activée" : "désactivée", utilisateurId);
        return utilisateur;
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public UtilisateurEntity trouver(UUID utilisateurId) {
        return charger(utilisateurId);
    }

    /** Liste filtrée : structureId, role, status — chaque filtre optionnel. */
    @Transactional(readOnly = true)
    public List<UtilisateurEntity> lister(UUID structureId, RoleUtilisateur role,
                                          StatutUtilisateur statut) {
        Specification<UtilisateurEntity> specification = Specification.where(null);
        if (structureId != null) {
            specification = specification.and((root, requete, cb) ->
                    cb.equal(root.get("structureId"), structureId));
        }
        if (role != null) {
            specification = specification.and((root, requete, cb) ->
                    cb.equal(root.get("role"), role));
        }
        if (statut != null) {
            specification = specification.and((root, requete, cb) ->
                    cb.equal(root.get("statut"), statut));
        }
        return utilisateurRepository.findAll(specification,
                Sort.by(Sort.Order.asc("nom"), Sort.Order.asc("email")));
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private UtilisateurEntity charger(UUID utilisateurId) {
        return utilisateurRepository.findById(utilisateurId)
                .orElseThrow(() -> new UtilisateurIntrouvableException(utilisateurId));
    }

    /** Une suspension ne se fait jamais en silence : le motif est OBLIGATOIRE. */
    private static void exigerRaison(String raison) {
        if (raison == null || raison.isBlank()) {
            throw new IllegalArgumentException(
                    "Le motif de suspension est OBLIGATOIRE (tracé en audit)");
        }
    }

    /** Anonyme → sentinel 000…0 (patron V10) : la trace d'audit est jamais null côté SQL. */
    private static UUID acteurOrSentinelle(UUID acteur) {
        return acteur == null ? UTILISATEUR_ANONYME : acteur;
    }

    /** Supabase Auth canonise les emails en minuscules — on fait de même. */
    private static String normaliserEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** null / blanc → null. */
    private static String blanchi(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    private void audit(UUID acteur, String action, String entite, UUID entiteId,
                       UUID facilityId, String raison, AuditEntryEntity.Result resultat,
                       Map<String, Object> details) {
        auditRecorder.record(acteur, action, entite, entiteId, facilityId, raison, resultat,
                details);
    }
}
