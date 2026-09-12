package bf.publichealth.common;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.RolesPermissions;

/**
 * Appelant courant — résolu depuis le contexte de sécurité DU FIL DE LA
 * REQUÊTE (jeton JWT décodé par Spring Security).
 *
 * <p>Suggestion 4 de l'audit de fidélité (« auditer les lectures, blinder
 * le 409 pour les anonymes ») : plusieurs garde-fous de défense en
 * profondeur doivent connaître, AU MOMENT où ils s'exécutent, qui appelle
 * et ce qu'il a le droit de voir. Cet utilitaire centralise cette
 * résolution — même logique que {@code FiltrePermissions.roleCourant}
 * et {@code PatientService.acteurCourant}, sans duplication.</p>
 *
 * <p>Résolution DÉFAILLANTE PAR SÉCURITÉ (fail-closed) :</p>
 * <ul>
 *   <li>pas d'authentification (posture ouverte, appel anonyme) →
 *       {@code role() == null}, {@code permission(...)} FAUX ;</li>
 *   <li>rôle {@code patient} (jeton autoporteur) → hors matrice staff →
 *       {@code permission(...)} FAUX ;</li>
 *   <li>rôle inconnu de la matrice → FAUX (jamais d'exception : les
 *       garde-fous n'ont pas le droit de casser le flux d'erreur).</li>
 * </ul>
 */
public final class ContexteAppelant {

    private ContexteAppelant() {
    }

    /** Identité (claim sub du JWT) — null si anonyme ou sub non UUID. */
    public static UUID acteur() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification instanceof JwtAuthenticationToken jeton
                && jeton.getToken().getSubject() != null) {
            try {
                return UUID.fromString(jeton.getToken().getSubject());
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return null;
    }

    /** Rôle applicatif (première autorité ROLE_x, en bas-de-casse) — null si anonyme. */
    public static String role() {
        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !authentification.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority autorite : authentification.getAuthorities()) {
            String nom = autorite.getAuthority();
            if (nom != null && nom.startsWith("ROLE_")) {
                return nom.substring("ROLE_".length()).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * L'appelant porte-t-il la permission ? FAUX par défaut : anonyme,
     * rôle patient, rôle inconnu ou permission absente de la matrice
     * ne voient RIEN. Seul un rôle STAFF de la matrice portant la
     * permission obtient VRAI.
     */
    public static boolean permission(String permission) {
        String role = role();
        if (role == null || RoleUtilisateur.ROLE_PATIENT.equals(role)) {
            return false;
        }
        try {
            return RolesPermissions.permissionsDe(RoleUtilisateur.depuisCode(role))
                    .contains(permission);
        } catch (IllegalArgumentException roleInconnu) {
            return false;
        }
    }
}
