package bf.publichealth.modules.laboratoire.adapter.web;

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

import bf.publichealth.modules.laboratoire.adapter.persistence.ExamenJdbc;
import bf.publichealth.modules.laboratoire.application.ExamenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API laboratoire — /api/v1/examens (V14, I6).
 *
 * <p>POST : demander un examen (laboratoire:ecrire — infirmier, médecin,
 * admin). POST /{id}/resultat : saisir le résultat (une seule fois).
 * GET : par patient / par statut (consultation:lire). Le patient
 * (jeton autoporteur) lit SES résultats.</p>
 */
@RestController
@RequestMapping("/api/v1/examens")
public class ExamenController {

    private final ExamenService examenService;

    public ExamenController(ExamenService examenService) {
        this.examenService = examenService;
    }

    @PostMapping
    public ResponseEntity<?> creer(@Valid @RequestBody CreationRequest requete,
                                   Authentication authentification) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(ExamenResponse.de(
                    examenService.creer(
                            new ExamenService.CommandeCreation(requete.patientId(),
                                    requete.consultationId(), requete.structureId(),
                                    requete.type()),
                            acteur(authentification))));
        } catch (ExamenService.ExamenRefuseException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Examen refusé");
            return ResponseEntity.badRequest().body(problem);
        }
    }

    @PostMapping("/{id}/resultat")
    public ResponseEntity<?> resultat(@PathVariable UUID id,
                                      @Valid @RequestBody ResultatRequest requete,
                                      Authentication authentification) {
        try {
            return ResponseEntity.ok(ExamenResponse.de(
                    examenService.enregistrerResultat(id, requete.resultatText(),
                            requete.resultatPositif(), acteur(authentification))));
        } catch (ExamenService.ExamenRefuseException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, e.getMessage());
            problem.setTitle("Résultat refusé");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
    }

    @GetMapping
    public ResponseEntity<List<ExamenResponse>> lister(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) String statut,
            Authentication authentification) {
        UUID patientEffectif = estPatient(authentification)
                ? claimPatientId(authentification) : patientId;
        return ResponseEntity.ok(examenService.lister(patientEffectif, statut).stream()
                .map(ExamenResponse::de).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExamenResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(ExamenResponse.de(examenService.trouver(id)));
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record CreationRequest(
            @NotNull(message = "Le patient est obligatoire") UUID patientId,
            UUID consultationId,
            UUID structureId,
            @NotBlank(message = "Le type d'examen est obligatoire") String type) {
    }

    public record ResultatRequest(
            @NotBlank(message = "Le résultat est obligatoire") String resultatText,
            Boolean resultatPositif) {
    }

    public record ExamenResponse(UUID id, UUID patientId, UUID consultationId, UUID structureId,
                                 String type, String statut, String resultatText,
                                 Boolean resultatPositif, String demandeLe,
                                 String resultatLe) {
        static ExamenResponse de(ExamenJdbc.Examen examen) {
            return new ExamenResponse(examen.id(), examen.patientId(), examen.consultationId(),
                    examen.structureId(), examen.type(), examen.statut(), examen.resultatText(),
                    examen.resultatPositif(), examen.demandeLe().toString(),
                    examen.resultatLe() == null ? null : examen.resultatLe().toString());
        }
    }

    private static boolean estPatient(Authentication authentification) {
        return authentification instanceof JwtAuthenticationToken jeton
                && "patient".equals(String.valueOf(jeton.getToken().getClaim("app_role")));
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

    private static UUID claimPatientId(Authentication authentification) {
        if (authentification instanceof JwtAuthenticationToken jeton
                && jeton.getToken().getClaim("patient_id") instanceof String claim) {
            try {
                return UUID.fromString(claim);
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return null;
    }
}
