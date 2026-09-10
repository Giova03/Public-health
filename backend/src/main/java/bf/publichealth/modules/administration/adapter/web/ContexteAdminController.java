package bf.publichealth.modules.administration.adapter.web;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.administration.application.UtilisateurContexteService;

/**
 * Contexte de l'administrateur appelant — /api/v1/admin/me.
 *
 * <p>{@code GET /api/v1/admin/me/permissions} : les permissions
 * effectives de l'appelant. Résolution de l'identité : le claim
 * {@code sub} du JWT actif (UUID du compte Supabase Auth) ; SANS JWT
 * — posture Sprint 0 — l'utilisateur SENTINEL 000…0 (patron V10 :
 * le compte anonyme matérialisé ; l'IT lie le sentinel par
 * activation pour vérifier l'annuaire sans infrastructure
 * d'authentification — documenté).</p>
 *
 * <p>Fonctionnement en staging/production (JWT actif) : la route
 * exige ROLE_ADMIN (SecurityConfig E5) ; le rôle et les permissions
 * font foi CÔTÉ BASE (miroir administration.utilisateur + matrice
 * role_permission), PAS depuis les claims du jeton — le claim
 * app_role ne porte que le rôle grossier du verrou HTTP.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/me")
public class ContexteAdminController {

    private final UtilisateurContexteService contexteService;

    public ContexteAdminController(UtilisateurContexteService contexteService) {
        this.contexteService = contexteService;
    }

    /**
     * Permissions effectives : {supabaseUserId, role, status,
     * permissions[], utilisateurConnu} — fail-closed (suspendu /
     * invite / inconnu → permissions vides).
     */
    @GetMapping("/permissions")
    public ResponseEntity<PermissionsResponse> permissions() {
        UtilisateurContexteService.ContextePermissions contexte =
                contexteService.permissionsCourantes();
        return ResponseEntity.ok(new PermissionsResponse(
                contexte.supabaseUserId(), contexte.role(), contexte.status(),
                Set.copyOf(contexte.permissions()).stream().sorted().toList(),
                contexte.utilisateurConnu()));
    }

    /** Les permissions effectives de l'appelant, triées. */
    public record PermissionsResponse(UUID supabaseUserId, String role, String status,
                                      java.util.List<String> permissions,
                                      boolean utilisateurConnu) {
    }
}
