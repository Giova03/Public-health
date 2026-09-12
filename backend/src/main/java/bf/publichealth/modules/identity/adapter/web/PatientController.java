package bf.publichealth.modules.identity.adapter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.identity.adapter.persistence.IdentityMatchEntity;
import bf.publichealth.modules.identity.application.PatientService;
import jakarta.validation.Valid;

/**
 * API identité & MPI — /api/v1/patients et /api/v1/identity/matches.
 *
 * <p>Le 409 doublons n'est PAS une erreur : c'est le contrat UX (ADR-003).
 * La charge contient les candidats avec leur score et leur verdict
 * (BLOCKING = quasi certain, REVIEW = zone grise) — l'agent décide,
 * l'API ne fusionne jamais en silence.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class PatientController {

    private static final int LIMITE_RECHERCHE = 20;

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    // ------------------------------------------------------------------
    // Patients
    // ------------------------------------------------------------------

    @PostMapping("/patients")
    public ResponseEntity<PatientDtos.PatientResponse> create(
            @Valid @RequestBody PatientDtos.CreatePatientRequest request) {

        var creation = patientService.create(new PatientService.CreatePatientCommand(
                request.clientRequestId(), request.forceCreate(), request.duplicateOfRejected(),
                request.gender(), request.birthDate(),
                Boolean.TRUE.equals(request.birthDateApproximative()),
                request.names().stream()
                        .map(n -> new PatientService.NameInput(n.use(), n.family(), n.given()))
                        .toList(),
                request.telecoms() == null ? List.of() : request.telecoms().stream()
                        .map(t -> new PatientService.TelecomInput(t.system(), t.value(), t.use()))
                        .toList(),
                request.identifiers() == null ? List.of() : request.identifiers().stream()
                        .map(i -> new PatientService.IdentifierInput(i.system(), i.value()))
                        .toList(),
                request.createdBy()));

        return ResponseEntity
                .status(creation.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PatientDtos.PatientResponse.from(creation.aggregate()));
    }

    @GetMapping("/patients/{id}")
    public ResponseEntity<PatientDtos.PatientResponse> get(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(PatientDtos.PatientResponse.from(patientService.find(id)));
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, e.getMessage());
            problem.setTitle("Patient introuvable");
            return ResponseEntity.of(problem).build();
        }
    }

    /**
     * Déclaration de décès (V14, I15) — permission consultation:ecrire
     * (FiltrePermissions). Le dossier est scellé : plus aucune consultation
     * ni RDV possible ; la cause alimente le rapport de mortalité SNIS.
     */
    @PostMapping("/patients/{id}/deces")
    public ResponseEntity<?> declarerDeces(
            @PathVariable UUID id,
            @Valid @RequestBody DeclarationDecesRequest requete) {
        try {
            var patient = patientService.declarerDeces(
                    id, requete.dateDeces(), requete.cause(), null);
            return ResponseEntity.ok(PatientDtos.PatientResponse.from(patient));
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.NOT_FOUND, e.getMessage());
            problem.setTitle("Patient introuvable");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        } catch (IllegalStateException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, e.getMessage());
            problem.setTitle("Décès déjà déclaré");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
    }

    /** Corps de la déclaration de décès. */
    public record DeclarationDecesRequest(
            java.time.Instant dateDeces,
            @jakarta.validation.constraints.NotBlank(message = "La cause du décès est obligatoire")
            String cause) {
    }

    /** Recherche miroir — mêmes critères que la détection à la création. */
    @GetMapping("/patients")
    public ResponseEntity<List<PatientDtos.PatientResponse>> search(
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String given,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) java.time.LocalDate birthDate) {
        return ResponseEntity.ok(patientService
                .search(family, given, phone, birthDate, LIMITE_RECHERCHE).stream()
                .map(PatientDtos.PatientResponse::from)
                .toList());
    }

    // ------------------------------------------------------------------
    // File de revue des rapprochements + fusion
    // ------------------------------------------------------------------

    @PostMapping("/identity/matches")
    public ResponseEntity<PatientDtos.MatchResponse> createMatch(
            @Valid @RequestBody PatientDtos.CreateMatchRequest request) {
        IdentityMatchEntity match = patientService.createMatch(
                request.candidateA(), request.candidateB(), request.method(), null);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(PatientDtos.MatchResponse.from(match));
    }

    @GetMapping("/identity/matches")
    public ResponseEntity<List<PatientDtos.MatchResponse>> matches() {
        return ResponseEntity.ok(patientService.matchesEnAttente().stream()
                .map(PatientDtos.MatchResponse::from)
                .toList());
    }

    @PostMapping("/identity/matches/{matchId}/review")
    public ResponseEntity<?> review(@PathVariable UUID matchId,
                                    @Valid @RequestBody PatientDtos.ReviewRequest request) {
        try {
            var outcome = patientService.reviewMatch(new PatientService.MatchReview(
                    matchId, request.decision(), request.masterId(),
                    request.motif(), request.reviewedBy()));
            if (outcome == null) {
                return ResponseEntity.ok().build();
            }
            record MergeOutcomeResponse(UUID mergeLogId, UUID masterId, UUID mergedId,
                                        PatientDtos.PatientResponse master) {
            }
            return ResponseEntity.ok(new MergeOutcomeResponse(
                    outcome.mergeLog().getId(), outcome.master().getId(),
                    outcome.merged().getId(),
                    PatientDtos.PatientResponse.from(patientService.find(outcome.master().getId()))));
        } catch (IllegalArgumentException | IllegalStateException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, e.getMessage());
            problem.setTitle("Revue impossible");
            return ResponseEntity.of(problem).build();
        }
    }

    /** Journal des fusions — public pour l'audit interne. */
    @GetMapping("/identity/merge-log")
    public ResponseEntity<List<PatientDtos.MergeLogResponse>> journal() {
        return ResponseEntity.ok(patientService.journalDesFusions().stream()
                .map(PatientDtos.MergeLogResponse::from)
                .toList());
    }
}
