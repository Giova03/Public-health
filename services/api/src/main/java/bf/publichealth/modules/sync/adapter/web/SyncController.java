package bf.publichealth.modules.sync.adapter.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.sync.application.SyncService;
import bf.publichealth.modules.sync.domain.AppareilInconnuException;
import bf.publichealth.modules.sync.domain.CurseurInvalideException;
import bf.publichealth.modules.sync.domain.EntiteDeltaInconnueException;
import jakarta.validation.Valid;

/**
 * API du protocole de synchronisation offline — /api/v1/sync*.
 *
 * <p>Le lot uplink répond TOUJOURS 200 : les verdicts par op
 * (APPLIED / REJECTED / CONFLICT) vivent DANS la réponse. Seules les
 * charges globalement invalides tombent en 400 (RFC 7807), traitées
 * localement ici pour ne pas dépendre du gestionnaire global.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync/device — déclaration idempotente d'appareil
    // ------------------------------------------------------------------

    @PostMapping("/sync/device")
    public ResponseEntity<SyncDtos.AppareilResponse> declarerAppareil(
            @Valid @RequestBody SyncDtos.DeclarationAppareilRequest request) {
        var appareil = syncService.declarerAppareil(new SyncService.DeclarationAppareil(
                request.userId(), request.deviceName()));
        return ResponseEntity
                .status(appareil.dejaConnu() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new SyncDtos.AppareilResponse(appareil.deviceId(), appareil.curseur()));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync — uplink par lot (le cœur du protocole)
    // ------------------------------------------------------------------

    @PostMapping("/sync")
    public ResponseEntity<SyncDtos.ReponseUplink> uplink(
            @Valid @RequestBody SyncDtos.RequeteUplink request) {
        var lot = new SyncService.LotBrut(request.deviceId(), request.userId(),
                request.ops().stream()
                        .map(op -> new SyncService.OpBrute(op.opId(), op.entity(), op.entityId(),
                                op.clientRequestId(), op.userId(), op.payload()))
                        .toList());
        var reponse = syncService.uplink(lot);
        return ResponseEntity.ok(new SyncDtos.ReponseUplink(reponse.results().stream()
                .map(r -> new SyncDtos.ResultatOpResponse(r.opId(), r.resultat(), r.detail()))
                .toList()));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/sync/delta — miroir descendant (patients modifiés/« créés »)
    // ------------------------------------------------------------------

    @GetMapping("/sync/delta")
    public ResponseEntity<SyncDtos.ReponseDelta> delta(
            @RequestParam(required = false) String cursor,
            @RequestParam(name = "entities", required = false) String entities,
            @RequestParam(required = false) Integer limit,
            @RequestParam(name = "deviceId", required = false) UUID deviceId) {
        var delta = syncService.delta(cursor, entities, limit, deviceId);
        return ResponseEntity.ok(new SyncDtos.ReponseDelta(delta.curseur(), delta.hasMore(),
                delta.entities(),
                delta.patients().stream().map(SyncDtos.PatientMiroirResponse::from).toList()));
    }

    // ------------------------------------------------------------------
    // 400 locaux (RFC 7807, même style que le GlobalExceptionHandler,
    // sans en dépendre — le module reste autonome)
    // ------------------------------------------------------------------

    @ExceptionHandler({AppareilInconnuException.class, CurseurInvalideException.class,
            EntiteDeltaInconnueException.class})
    public ResponseEntity<ProblemDetail> requeteInvalide(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Requête de synchronisation refusée");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> chargeIllisible(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Charge JSON illisible : " + e.getMostSpecificCause().getMessage());
        problem.setTitle("Charge invalide");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> parametreIllisible(MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Paramètre invalide : " + e.getName());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
