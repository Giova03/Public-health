package bf.publichealth.modules.administration.adapter.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.administration.adapter.persistence.UtilisateurEntity;
import bf.publichealth.modules.administration.application.UtilisateurService;
import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.StatutUtilisateur;
import jakarta.validation.Valid;

/**
 * API d'administration des utilisateurs — /api/v1/admin/users
 * (épique E6).
 *
 * <p>En posture JWT actif (E5), la route exige ROLE_ADMIN
 * ({@code /api/v1/admin/**} dans la SecurityConfig — aucune
 * modification n'y est nécessaire) ; en posture Sprint 0 (défaut),
 * la route est ouverte comme le reste de l'API. AUCUN mot de passe
 * ne transite ici : l'authentification vit chez Supabase Auth, on ne
 * lie que le miroir {@code supabaseUserId} à l'activation.</p>
 *
 * <p>Toutes les transitions (activation, suspension, réactivation,
 * changement de rôle, MFA) sont tracées en audit ; les refus
 * illégaux sont des 409 explicites, jamais des double-changements
 * en silence. Un utilisateur ne se supprime JAMAIS — la suspension
 * est la seule issue.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UtilisateurController {

    private final UtilisateurService utilisateurService;

    public UtilisateurController(UtilisateurService utilisateurService) {
        this.utilisateurService = utilisateurService;
    }

    /** Invitation : 201 + Location ; email déjà pris (toute casse) → 409. */
    @PostMapping
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> inviter(
            @Valid @RequestBody UtilisateurDtos.InviterUtilisateurRequest request) {
        UtilisateurEntity utilisateur = utilisateurService.inviter(
                new UtilisateurService.CommandeInvitation(
                        request.email(), RoleUtilisateur.depuisCode(request.role()),
                        request.nom(), request.prenoms(), request.structureId(),
                        request.invitedBy()),
                acteurCourant());
        return ResponseEntity
                .created(URI.create("/api/v1/admin/users/" + utilisateur.getId()))
                .body(UtilisateurDtos.UtilisateurResponse.from(utilisateur));
    }

    /** Portrait administratif complet. */
    @GetMapping("/{id}")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.trouver(id)));
    }

    /** Liste filtrée : structureId, role, status — chaque filtre optionnel. */
    @GetMapping
    public ResponseEntity<?> lister(
            @RequestParam(required = false) UUID structureId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        RoleUtilisateur roleFiltre = role == null || role.isBlank()
                ? null : RoleUtilisateur.depuisCode(role);
        StatutUtilisateur statutFiltre = status == null || status.isBlank()
                ? null : StatutUtilisateur.depuisCode(status);
        List<UtilisateurDtos.UtilisateurResponse> utilisateurs =
                utilisateurService.lister(structureId, roleFiltre, statutFiltre).stream()
                        .map(UtilisateurDtos.UtilisateurResponse::from)
                        .toList();
        return ResponseEntity.ok(utilisateurs);
    }

    /** Activation : lie le compte Supabase (définitif) ; déjà lié → 409. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> activer(
            @PathVariable UUID id,
            @Valid @RequestBody UtilisateurDtos.ActiverUtilisateurRequest request) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.activer(id, request.supabaseUserId(), acteurCourant())));
    }

    /** Suspension : motif OBLIGATOIRE (tracé) ; pas actif → 409. */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> suspendre(
            @PathVariable UUID id,
            @Valid @RequestBody UtilisateurDtos.SuspendreUtilisateurRequest request) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.suspendre(id, request.reason(), acteurCourant())));
    }

    /** Réactivation : pas suspendu → 409. */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> reactiver(
            @PathVariable UUID id) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.reactiver(id, acteurCourant())));
    }

    /** Changement de rôle : TRACÉ from → to en audit. */
    @PostMapping("/{id}/role")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> changerRole(
            @PathVariable UUID id,
            @Valid @RequestBody UtilisateurDtos.ChangerRoleRequest request) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.changerRole(id,
                        RoleUtilisateur.depuisCode(request.role()), acteurCourant())));
    }

    /** Bascule MFA : TRACÉE (miroir de l'état du compte Supabase Auth). */
    @PostMapping("/{id}/mfa")
    public ResponseEntity<UtilisateurDtos.UtilisateurResponse> basculerMfa(
            @PathVariable UUID id,
            @Valid @RequestBody UtilisateurDtos.BasculerMfaRequest request) {
        return ResponseEntity.ok(UtilisateurDtos.UtilisateurResponse.from(
                utilisateurService.basculerMfa(id, request.active(), acteurCourant())));
    }

    // ------------------------------------------------------------------
    // Contexte sécurité : l'auteur depuis le JWT actif, sinon null
    // (le service trace alors l'anonyme sentinel 000…0 — patron V10).
    // ------------------------------------------------------------------

    private UUID acteurCourant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
