package bf.publichealth.modules.consultation.adapter.web;

import java.util.LinkedHashMap;
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

import bf.publichealth.modules.consultation.adapter.persistence.ConsultationJdbc;
import bf.publichealth.modules.consultation.application.ConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API consultation — /api/v1/consultations (P0.5, audit I4).
 *
 * <p>Création complète (motif + constantes + notes + diagnostic codé),
 * lecture par patient et par id. Le patient (jeton autoporteur) ne
 * voit QUE ses consultations (périmètre vérifié par FiltrePermissions).</p>
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultationController {

    private final ConsultationService consultationService;

    public ConsultationController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    /** Création — 201 ; le corps porte TOUT l'acte clinique. */
    @PostMapping
    public ResponseEntity<?> creer(@Valid @RequestBody CreationRequest requete,
                                   Authentication authentification) {
        try {
            Map<String, Double> constantes = new LinkedHashMap<>();
            if (requete.constantes() != null) {
                if (requete.constantes().taSystolique() != null) {
                    constantes.put("TA_SYSTOLIQUE", requete.constantes().taSystolique());
                }
                if (requete.constantes().taDiastolique() != null) {
                    constantes.put("TA_DIASTOLIQUE", requete.constantes().taDiastolique());
                }
                if (requete.constantes().temperatureC() != null) {
                    constantes.put("TEMPERATURE", requete.constantes().temperatureC());
                }
                if (requete.constantes().poidsKg() != null) {
                    constantes.put("POIDS", requete.constantes().poidsKg());
                }
            }
            ConsultationJdbc.ConsultationLue consult = consultationService.creer(
                    new ConsultationService.CommandeCreation(
                            requete.clientRequestId(), requete.patientId(), requete.facilityId(),
                            acteur(authentification), requete.motif(),
                            requete.diagnosticCode(), requete.diagnosticLabel(),
                            requete.notes(), constantes),
                    acteur(authentification));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ConsultationResponse.de(consult));
        } catch (ConsultationService.PatientInvalideException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    e.getMessage().contains("scellé") ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                    e.getMessage());
            problem.setTitle("Consultation impossible");
            return ResponseEntity.of(problem).build();
        }
    }

    /** Dossier clinique d'un patient — la plus récente d'abord. */
    @GetMapping
    public ResponseEntity<List<ConsultationResponse>> parPatient(
            @RequestParam UUID patientId) {
        return ResponseEntity.ok(
                consultationService.parPatient(patientId).stream()
                        .map(ConsultationResponse::de)
                        .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConsultationResponse> trouver(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(ConsultationResponse.de(consultationService.trouver(id)));
        } catch (ConsultationService.PatientInvalideException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, e.getMessage());
            problem.setTitle("Consultation introuvable");
            return ResponseEntity.of(problem).build();
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record ConstantesRequest(Double taSystolique, Double taDiastolique,
                                    Double temperatureC, Double poidsKg) {
    }

    public record CreationRequest(
            UUID clientRequestId,
            @NotNull(message = "Le patient est obligatoire") UUID patientId,
            @NotNull(message = "La structure est obligatoire") UUID facilityId,
            String motif,
            @NotBlank(message = "Le diagnostic codé est obligatoire") String diagnosticCode,
            String diagnosticLabel,
            String notes,
            ConstantesRequest constantes) {
    }

    public record ConsultationResponse(UUID id, UUID patientId, UUID facilityId,
                                       UUID practitionerId, String motif,
                                       String diagnosticCode, String diagnosticLabel,
                                       String notes, Map<String, Double> constantes,
                                       String date, List<UUID> examens) {
        static ConsultationResponse de(ConsultationJdbc.ConsultationLue consult) {
            return new ConsultationResponse(consult.id(), consult.patientId(),
                    consult.facilityId(), consult.practitionerId(), consult.motif(),
                    consult.diagnosticCode(), consult.diagnosticLabel(), consult.notes(),
                    consult.constantes(), consult.date().toString(), consult.examens());
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
}
