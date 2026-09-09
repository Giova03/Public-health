package bf.publichealth.modules.organization.adapter.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.organization.application.StructureService;
import bf.publichealth.modules.organization.domain.TypeStructure;
import jakarta.validation.Valid;

/**
 * API annuaire des structures sanitaires — /api/v1/organizations
 * (épique E6).
 *
 * <p>Lecture utile aux agents (recherche, arborescence) et écriture
 * d'administration. En posture JWT actif (E5), la route est couverte
 * par {@code /api/v1/**} (authentifiée) — la politique fine
 * d'écriture arrive avec le RBAC E6. En posture Sprint 0 (défaut),
 * la route est ouverte comme le reste de l'API.</p>
 *
 * <p>Désactivation/réactivation SOFT : 409 si la structure est déjà
 * dans l'état demandé — jamais de double changement en silence, et
 * JAMAIS de DELETE (l'annuaire est la référence historique).</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class StructureController {

    private final StructureService structureService;

    public StructureController(StructureService structureService) {
        this.structureService = structureService;
    }

    /** Création : 201 + Location ; mnémonique déjà prise → 409. */
    @PostMapping
    public ResponseEntity<StructureDtos.StructureResponse> creer(
            @Valid @RequestBody StructureDtos.CreateStructureRequest request) {
        var structure = structureService.creer(
                new StructureService.CommandeCreation(
                        request.code(), request.nom(),
                        TypeStructure.depuisCode(request.type()),
                        request.region(), request.province(), request.commune(),
                        request.latitude(), request.longitude()),
                acteurCourant());
        return ResponseEntity
                .created(URI.create("/api/v1/organizations/" + structure.getId()))
                .body(StructureDtos.StructureResponse.from(structure));
    }

    /** Vue complète d'une structure. */
    @GetMapping("/{id}")
    public ResponseEntity<StructureDtos.StructureResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(StructureDtos.StructureResponse.from(
                structureService.trouver(id)));
    }

    /**
     * Recherche de l'annuaire : région, type, texte (code ou nom),
     * active — chaque filtre optionnel.
     */
    @GetMapping
    public ResponseEntity<?> rechercher(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String texte,
            @RequestParam(required = false) Boolean active) {
        TypeStructure typeStructure = type == null || type.isBlank()
                ? null : TypeStructure.depuisCode(type);
        return ResponseEntity.ok(StructureDtos.liste(
                structureService.rechercher(region, typeStructure, texte, active)));
    }

    /** Arborescence par région : total, actives, répartition par type. */
    @GetMapping("/arborescence")
    public ResponseEntity<?> arborescence() {
        return ResponseEntity.ok(structureService.arborescence().stream()
                .map(StructureDtos.ArborescenceResponse::from)
                .toList());
    }

    /** Mise à jour nom / type / localisation (activation à part). */
    @PatchMapping("/{id}")
    public ResponseEntity<StructureDtos.StructureResponse> mettreAJour(
            @PathVariable UUID id,
            @Valid @RequestBody StructureDtos.UpdateStructureRequest request) {
        var structure = structureService.mettreAJour(id,
                new StructureService.MiseAJour(
                        request.nom(),
                        request.type() == null ? null : TypeStructure.depuisCode(request.type()),
                        request.region(), request.province(), request.commune(),
                        request.latitude(), request.longitude()),
                acteurCourant());
        return ResponseEntity.ok(StructureDtos.StructureResponse.from(structure));
    }

    /** Réactivation : 409 si déjà active. */
    @PostMapping("/{id}/activate")
    public ResponseEntity<StructureDtos.StructureResponse> activer(@PathVariable UUID id) {
        return ResponseEntity.ok(StructureDtos.StructureResponse.from(
                structureService.reactiver(id, acteurCourant())));
    }

    /** Désactivation SOFT : 409 si déjà inactive — jamais de DELETE. */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<StructureDtos.StructureResponse> desactiver(@PathVariable UUID id) {
        return ResponseEntity.ok(StructureDtos.StructureResponse.from(
                structureService.desactiver(id, acteurCourant())));
    }

    // ------------------------------------------------------------------
    // Contexte sécurité : l'auteur depuis le JWT actif, sinon null
    // (le service trace alors l'anonyme documenté — posture Sprint 0).
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
