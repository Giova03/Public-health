package bf.publichealth.modules.payments.adapter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.payments.adapter.persistence.FraisAccesJdbc.FraisAccesLue;
import bf.publichealth.modules.payments.application.FraisAccesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * API frais d'accès — /api/v1/frais-acces (I5, P0.5-6).
 *
 * <p>La caisse du CSPS : ouverture du ticket du jour (idempotente),
 * encaissement espèces, exonération TRACÉE (nature + motif obligatoires),
 * file d'attente et historique. Permissions : paiement:initier pour les
 * écritures, paiement:lire pour les lectures (FiltrePermissions).</p>
 */
@RestController
@RequestMapping("/api/v1/frais-acces")
public class FraisAccesController {

    private final FraisAccesService fraisAcces;

    public FraisAccesController(FraisAccesService fraisAcces) {
        this.fraisAcces = fraisAcces;
    }

    /** Ouverture du ticket du jour — 201 (nouveau) ou 200 (rejeu idempotent). */
    @PostMapping
    public ResponseEntity<?> ouvrir(@Valid @RequestBody OuvertureRequest requete,
                                                     Authentication authentification) {
        try {
            boolean nouveau = fraisAcces.statutReglementAujourdhui(
                    requete.patientId(), requete.structureId()) == null;
            FraisAccesLue ticket = fraisAcces.ouvrir(
                    requete.patientId(), requete.structureId(), requete.montantXof(),
                    acteur(authentification));
            return ResponseEntity.status(nouveau ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(FraisAccesResponse.de(ticket));
        } catch (FraisAccesService.FraisAccesInvalideException e) {
            return probleme(HttpStatus.BAD_REQUEST, "Ticket impossible", e.getMessage());
        }
    }

    /** Encaissement espèces — forward-only, terminal. */
    @PostMapping("/{id}/encaisser")
    public ResponseEntity<?> encaisser(@PathVariable UUID id,
                                       @RequestBody(required = false) EncaissementRequest requete,
                                       Authentication authentification) {
        try {
            Long montant = requete == null ? null : requete.montantXof();
            return ResponseEntity.ok(FraisAccesResponse.de(
                    fraisAcces.encaisser(id, montant, acteur(authentification))));
        } catch (FraisAccesService.FraisAccesInvalideException e) {
            HttpStatus statut = e.getMessage().contains("forward-only")
                    ? HttpStatus.CONFLICT
                    : e.getMessage().startsWith("Ticket introuvable")
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.BAD_REQUEST;
            return probleme(statut, "Encaissement impossible", e.getMessage());
        }
    }

    /** Exonération — nature CONNUE + motif OBLIGATOIRE, forward-only. */
    @PostMapping("/{id}/exonerer")
    public ResponseEntity<?> exonerer(@Valid @RequestBody ExonerationRequest requete,
                                      @PathVariable UUID id,
                                      Authentication authentification) {
        try {
            return ResponseEntity.ok(FraisAccesResponse.de(
                    fraisAcces.exonerer(id, requete.nature(), requete.motif(),
                            acteur(authentification))));
        } catch (FraisAccesService.FraisAccesInvalideException e) {
            HttpStatus statut = e.getMessage().contains("forward-only")
                    ? HttpStatus.CONFLICT
                    : e.getMessage().startsWith("Ticket introuvable")
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.BAD_REQUEST;
            return probleme(statut, "Exonération impossible", e.getMessage());
        }
    }

    /**
     * Consultation des tickets : par patient (historique), ou la FILE
     * D'ATTENTE de la caisse (structureId + statut=en_attente).
     */
    @GetMapping
    public ResponseEntity<?> rechercher(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID structureId,
            @RequestParam(required = false) String statut) {
        if (patientId != null) {
            List<FraisAccesResponse> tickets = fraisAcces.historique(
                    patientId, structureId, statut).stream()
                    .map(FraisAccesResponse::de).toList();
            return ResponseEntity.ok(tickets);
        }
        if (structureId != null && "en_attente".equalsIgnoreCase(statut)) {
            return ResponseEntity.ok(fraisAcces.fileAttente(structureId).stream()
                    .map(FraisAccesResponse::de).toList());
        }
        return probleme(HttpStatus.BAD_REQUEST, "Recherche incomplète",
                "patientId (historique) ou structureId + statut=en_attente (file caisse) requis");
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> trouver(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(FraisAccesResponse.de(fraisAcces.trouver(id)));
        } catch (FraisAccesService.FraisAccesInvalideException e) {
            return probleme(HttpStatus.NOT_FOUND, "Ticket introuvable", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record OuvertureRequest(
            @NotNull(message = "Le patient est obligatoire") UUID patientId,
            @NotNull(message = "La structure est obligatoire") UUID structureId,
            Long montantXof) {
    }

    public record EncaissementRequest(Long montantXof) {
    }

    public record ExonerationRequest(
            @NotNull(message = "La nature d'exonération est obligatoire") String nature,
            @NotNull(message = "Le motif d'exonération est obligatoire") String motif) {
    }

    public record FraisAccesResponse(UUID id, UUID patientId, UUID structureId,
                                     String statut, long montantXof,
                                     String exonerationNature, String exonerationMotif,
                                     String date) {
        static FraisAccesResponse de(FraisAccesLue ticket) {
            return new FraisAccesResponse(ticket.id(), ticket.patientId(),
                    ticket.structureId(), ticket.statut(), ticket.montantXof(),
                    ticket.exonerationNature(), ticket.exonerationMotif(),
                    ticket.createdAt().toString());
        }
    }

    private static ResponseEntity<ProblemDetail> probleme(HttpStatus statut, String titre,
                                                          String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statut, detail);
        problem.setTitle(titre);
        return ResponseEntity.of(problem).build();
    }

    private static UUID acteur(Authentication authentification) {
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
}
