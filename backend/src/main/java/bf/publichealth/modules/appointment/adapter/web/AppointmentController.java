package bf.publichealth.modules.appointment.adapter.web;

import java.time.Instant;
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

import bf.publichealth.modules.appointment.adapter.persistence.AppointmentJdbc;
import bf.publichealth.modules.appointment.application.AppointmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API rendez-vous — /api/v1/appointments (P1, audit : RDV absent).
 *
 * <p>Le PATIENT (jeton autoporteur) : crée SES demandes (patient_id
 * FORCÉ au claim — jamais le corps), annule les siennes (motif obligatoire),
 * liste les siennes. L'AGENT (rendezvous:gerer) : confirme, honore,
 * consigne l'absence, liste par structure.</p>
 */
@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<?> creer(@Valid @RequestBody CreationRequest requete,
                                   Authentication authentification) {
        UUID acteur = acteur(authentification);
        boolean patient = estPatient(authentification);
        try {
            // Un patient ne crée JAMAIS pour un autre : patient_id forcé au claim.
            UUID patientId = patient ? claimPatientId(authentification) : requete.patientId();
            if (patientId == null) {
                throw new AppointmentService.RdvRefuseException("Jeton patient invalide");
            }
            AppointmentJdbc.RendezVous rdv = appointmentService.creer(
                    new AppointmentService.CommandeCreation(
                            patientId,
                            requete.structureId() == null ? claimStructure(authentification)
                                    : requete.structureId(),
                            requete.practitionerId(),
                            requete.type(),
                            requete.creneau(),
                            requete.motif(),
                            patient ? "patient" : "agent",
                            requete.clientRequestId()),
                    acteur);
            return ResponseEntity.status(HttpStatus.CREATED).body(RdvResponse.de(rdv));
        } catch (AppointmentService.RdvRefuseException e) {
            HttpStatus statut = e.getMessage().contains("Quota")
                    ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(statut, e.getMessage());
            problem.setTitle("Rendez-vous refusé");
            return ResponseEntity.status(statut).body(problem);
        }
    }

    @GetMapping
    public ResponseEntity<List<RdvResponse>> lister(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID structureId,
            @RequestParam(required = false) String statut,
            Authentication authentification) {
        UUID patientEffectif = estPatient(authentification)
                ? claimPatientId(authentification) : patientId;
        return ResponseEntity.ok(appointmentService.lister(patientEffectif, structureId, statut)
                .stream().map(RdvResponse::de).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RdvResponse> trouver(@PathVariable UUID id) {
        return ResponseEntity.ok(RdvResponse.de(appointmentService.trouver(id)));
    }

    @PostMapping("/{id}/confirmer")
    public ResponseEntity<?> confirmer(@PathVariable UUID id, Authentication authentification) {
        return transition(id, authentification, "confirmer", null);
    }

    @PostMapping("/{id}/honorer")
    public ResponseEntity<?> honorer(@PathVariable UUID id, Authentication authentification) {
        return transition(id, authentification, "honorer", null);
    }

    @PostMapping("/{id}/absent")
    public ResponseEntity<?> absent(@PathVariable UUID id, Authentication authentification) {
        return transition(id, authentification, "absent", null);
    }

    @PostMapping("/{id}/annuler")
    public ResponseEntity<?> annuler(@PathVariable UUID id,
                                     @Valid @RequestBody AnnulationRequest requete,
                                     Authentication authentification) {
        // Un patient n'annule que SON rendez-vous (propriété vérifiée CÔTÉ API).
        if (estPatient(authentification)) {
            UUID patientId = claimPatientId(authentification);
            var rdv = appointmentService.trouver(id);
            if (patientId == null || !patientId.equals(rdv.patientId())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.FORBIDDEN,
                        "Un patient n'annule que son propre rendez-vous");
                problem.setTitle("Accès refusé");
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(problem);
            }
        }
        return transition(id, authentification, "annuler", requete.motif());
    }

    private ResponseEntity<?> transition(UUID id, Authentication authentification, String action,
                                         String motif) {
        try {
            AppointmentJdbc.RendezVous rdv = switch (action) {
                case "confirmer" -> appointmentService.confirmer(id, acteur(authentification));
                case "honorer" -> appointmentService.honorer(id, acteur(authentification));
                case "absent" -> appointmentService.absent(id, acteur(authentification));
                default -> appointmentService.annuler(id, motif, acteur(authentification));
            };
            return ResponseEntity.ok(RdvResponse.de(rdv));
        } catch (AppointmentService.RdvRefuseException e) {
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
            UUID patientId,
            UUID structureId,
            UUID practitionerId,
            String type,
            @NotNull(message = "Le créneau est obligatoire") Instant creneau,
            String motif,
            UUID clientRequestId) {
    }

    public record AnnulationRequest(
            @NotBlank(message = "Le motif d'annulation est obligatoire") String motif) {
    }

    public record RdvResponse(UUID id, UUID patientId, UUID structureId, UUID practitionerId,
                              String type, String creneau, String statut, String motif,
                              String demandePar, String motifAnnulation) {
        static RdvResponse de(AppointmentJdbc.RendezVous rdv) {
            return new RdvResponse(rdv.id(), rdv.patientId(), rdv.structureId(),
                    rdv.practitionerId(), rdv.type(), rdv.creneau().toString(), rdv.statut(),
                    rdv.motif(), rdv.demandePar(), rdv.motifAnnulation());
        }
    }

    // ------------------------------------------------------------------
    // Contexte du jeton
    // ------------------------------------------------------------------

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
