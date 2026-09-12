package bf.publichealth.modules.reference.adapter.web;

import java.util.List;
import java.util.Map;
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

import bf.publichealth.modules.reference.adapter.persistence.ReferenceJdbc;
import bf.publichealth.modules.reference.application.ReferenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API référence / contre-référence — /api/v1/references (V14, I7).
 *
 * <p>La continuité des soins CSPS→CMA/CHR est TRACÉE : envoi (motif
 * obligatoire), réception par la structure destinataire, hospitalisation
 * éventuelle, contre-référence (résumé OBLIGATOIRE) au retour. Les
 * statistiques signalent les non-abouties (envoyées > 48 h).</p>
 */
@RestController
@RequestMapping("/api/v1/references")
public class ReferenceController {

    private final ReferenceService referenceService;

    public ReferenceController(ReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    /** Initier une référence — l'origine est la structure du jeton (jamais déclarée). */
    @PostMapping
    public ResponseEntity<?> creer(@Valid @RequestBody CreationRequest requete,
                                   Authentication authentification) {
        try {
            UUID origine = requete.structureOrigine() == null
                    ? claimStructure(authentification) : requete.structureOrigine();
            if (origine == null) {
                throw new ReferenceService.ReferenceRefuseeException(
                        "Structure d'origine inconnue : précisez-la ou connectez-vous avec un compte rattaché");
            }
            ReferenceJdbc.Fiche fiche = referenceService.creer(
                    new ReferenceService.CommandeCreation(requete.patientId(), origine,
                            requete.structureDestination(), requete.motif(), requete.urgence(),
                            requete.clientRequestId()),
                    acteur(authentification));
            return ResponseEntity.status(HttpStatus.CREATED).body(FicheResponse.de(fiche));
        } catch (ReferenceService.ReferenceRefuseeException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Référence refusée");
            return ResponseEntity.badRequest().body(problem);
        }
    }

    @GetMapping
    public ResponseEntity<List<FicheResponse>> lister(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID structureId,
            @RequestParam(required = false) String statut) {
        return ResponseEntity.ok(referenceService.lister(patientId, structureId, statut)
                .stream().map(FicheResponse::de).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FicheResponse> trouver(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(FicheResponse.de(referenceService.trouver(id)));
        } catch (ReferenceService.ReferenceRefuseeException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, e.getMessage());
            problem.setTitle("Référence introuvable");
            return ResponseEntity.of(problem).build();
        }
    }

    /** La structure destinataire confirme la réception. */
    @PostMapping("/{id}/reception")
    public ResponseEntity<?> reception(@PathVariable UUID id, Authentication authentification) {
        return transition(id, authentification, "reception");
    }

    @PostMapping("/{id}/hospitalisation")
    public ResponseEntity<?> hospitalisation(@PathVariable UUID id, Authentication authentification) {
        return transition(id, authentification, "hospitalisation");
    }

    /** Contre-référence : le résumé OBLIGATOIRE referme la boucle. */
    @PostMapping("/{id}/contre-reference")
    public ResponseEntity<?> contreReference(@PathVariable UUID id,
                                             @Valid @RequestBody ContreReferenceRequest requete,
                                             Authentication authentification) {
        try {
            return ResponseEntity.ok(FicheResponse.de(
                    referenceService.contreReferencer(id, requete.resume(),
                            acteur(authentification))));
        } catch (ReferenceService.ReferenceRefuseeException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, e.getMessage());
            problem.setTitle("Contre-référence refusée");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
    }

    private ResponseEntity<?> transition(UUID id, Authentication authentification, String action) {
        try {
            ReferenceJdbc.Fiche fiche = "reception".equals(action)
                    ? referenceService.recevoir(id, acteur(authentification))
                    : referenceService.hospitaliser(id, acteur(authentification));
            return ResponseEntity.ok(FicheResponse.de(fiche));
        } catch (ReferenceService.ReferenceRefuseeException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, e.getMessage());
            problem.setTitle("Transition refusée");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record CreationRequest(
            @NotNull(message = "Le patient est obligatoire") UUID patientId,
            UUID structureOrigine,
            @NotNull(message = "La structure de destination est obligatoire") UUID structureDestination,
            @NotBlank(message = "Le motif de la référence est obligatoire") String motif,
            boolean urgence,
            UUID clientRequestId) {
    }

    public record ContreReferenceRequest(
            @NotBlank(message = "Le résumé de contre-référence est obligatoire") String resume) {
    }

    public record FicheResponse(UUID id, UUID patientId, UUID structureOrigine,
                                UUID structureDestination, String motif, boolean urgence,
                                String statut, String createdAt, String recueLe,
                                String contreReference) {
        static FicheResponse de(ReferenceJdbc.Fiche fiche) {
            return new FicheResponse(fiche.id(), fiche.patientId(), fiche.structureOrigine(),
                    fiche.structureDestination(), fiche.motif(), fiche.urgence(), fiche.statut(),
                    fiche.createdAt().toString(),
                    fiche.recueLe() == null ? null : fiche.recueLe().toString(),
                    fiche.contreReference());
        }
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

    private static UUID claimStructure(Authentication authentification) {
        if (authentification instanceof JwtAuthenticationToken jeton
                && jeton.getToken().getClaim("structure_id") instanceof String claim) {
            try {
                return UUID.fromString(claim);
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return null;
    }
}
