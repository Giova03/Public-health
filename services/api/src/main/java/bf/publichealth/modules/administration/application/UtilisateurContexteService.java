package bf.publichealth.modules.administration.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.administration.adapter.persistence.RolePermissionRepository;
import bf.publichealth.modules.administration.adapter.persistence.UtilisateurRepository;

/**
 * Contexte d'permissions effectives — traduit (supabase_user_id, rôle)
 * en permissions pour la couche HTTP et les modules à venir (RBAC fin
 * de l'épique E6, utilisé par /api/v1/admin/me/permissions).
 *
 * <p>Résolution de l'appelant : le claim {@code sub} du JWT actif
 * (l'UUID du compte Supabase Auth) ; SANS JWT — posture Sprint 0 —
 * l'appelant est l'utilisateur SENTINEL 000…0 (patron V10 : le compte
 * anonyme matérialisé, celui que la dernière activation a lié en
 * l'absence de contexte sécurité — documenté, le verrou HTTP réel
 * vit dans la SecurityConfig E5).</p>
 *
 * <p>Source des permissions : la table {@code administration.role_permission}
 * (valeur de référence semée par V12 à l'identique du domaine
 * RolesPermissions — égalité verrouillée par BackofficeIT). Le rôle
 * fait foi CÔTÉ BASE : le claim app_role du JWT ne porte que le rôle
 * grossier ROLE_ADMIN de la SecurityConfig ; ici, le miroir
 * administration.utilisateur décide.</p>
 *
 * <p>Fail-closed : un utilisateur introuvable, non encore activé
 * (invite) ou SUSPENDU ne porte AUCUNE permission effective — la
 * suspension coupe tout, sans exception.</p>
 */
@Service
public class UtilisateurContexteService {

    /** L'utilisateur anonyme — posture Sprint 0 sans JWT (patron V10). */
    public static final UUID UTILISATEUR_ANONYME =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Permissions effectives de l'appelant du contexte courant. */
    public record ContextePermissions(UUID supabaseUserId, String role, String status,
                                      Set<String> permissions, boolean utilisateurConnu) {
    }

    private final UtilisateurRepository utilisateurRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public UtilisateurContexteService(UtilisateurRepository utilisateurRepository,
                                      RolePermissionRepository rolePermissionRepository) {
        this.utilisateurRepository = utilisateurRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    /**
     * Résout les permissions effectives DE L'APPELANT courant :
     * sub du JWT actif, sinon l'utilisateur sentinel 000…0 (posture
     * Sprint 0, documenté — l'activation lie le sentinel pour que
     * l'annuaire des comptes reste vérifiable sans infrastructure
     * d'authentification).
     */
    @Transactional(readOnly = true)
    public ContextePermissions permissionsCourantes() {
        return permissionsDe(appelantCourant());
    }

    /**
     * Résout les permissions effectives d'un porteur de compte
     * Supabase. Fail-closed : introuvable / invite / suspendu →
     * AUCUNE permission (le suspendu ne garde rien).
     */
    @Transactional(readOnly = true)
    public ContextePermissions permissionsDe(UUID supabaseUserId) {
        if (supabaseUserId == null) {
            return inconnu(null);
        }
        return utilisateurRepository.findBySupabaseUserId(supabaseUserId)
                .map(utilisateur -> {
                    if (!utilisateur.getStatut().estActif()) {
                        // invite : le compte Supabase n'est pas encore lié ;
                        // suspendu : la suspension coupe TOUT — fail-closed.
                        return new ContextePermissions(supabaseUserId,
                                utilisateur.getRole().getCode(),
                                utilisateur.getStatut().getCode(), Set.of(), true);
                    }
                    return new ContextePermissions(supabaseUserId,
                            utilisateur.getRole().getCode(),
                            utilisateur.getStatut().getCode(),
                            permissionsDepuisBase(utilisateur.getRole().getCode()), true);
                })
                .orElseGet(() -> inconnu(supabaseUserId));
    }

    /** Permissions du rôle, lues dans la table de référence V12 (triées et immuables). */
    @Transactional(readOnly = true)
    public Set<String> permissionsDepuisBase(String roleCode) {
        Set<String> permissions = new LinkedHashSet<>();
        for (var ligne : rolePermissionRepository.findByRoleOrderByIdAsc(roleCode)) {
            permissions.add(ligne.getPermission());
        }
        return Set.copyOf(permissions);
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private static ContextePermissions inconnu(UUID supabaseUserId) {
        return new ContextePermissions(supabaseUserId, null, null, Set.of(), false);
    }

    /**
     * L'appelant du contexte sécurité : sub du JWT (UUID du compte
     * Supabase), sinon l'anonyme sentinel 000…0. Un sub non-UUID est
     * traité comme anonyme (fail-closed, consigné).
     */
    private static UUID appelantCourant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return UTILISATEUR_ANONYME;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return UTILISATEUR_ANONYME;
        }
    }
}
