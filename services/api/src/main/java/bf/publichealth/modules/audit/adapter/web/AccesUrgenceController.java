package bf.publichealth.modules.audit.adapter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.audit.application.AccesUrgenceService;
import bf.publichealth.modules.audit.domain.AccesUrgenceIntrouvableException;
import jakarta.validation.Valid;

/**
 * API du break-the-glass — /api/v1/audit/*.
 *
 * <p>L'accès d'urgence n'est JAMAIS bloquant : ouvert en 201, tracé par
 * une entrée d'audit chaînée (BREAK_THE_GLASS), il est ensuite LU
 * (historique du patient, file des non-revus) et REVU a posteriori.
 * {@code userId} provient du contexte sécurité quand un JWT est actif,
 * sinon il est null (documenté — posture Sprint 0).</p>
 *
 * <p>Les 400/404 locaux suivent le RFC 7807, même style que le
 * GlobalExceptionHandler, sans en dépendre — le module reste autonome
 * (patron SyncController).</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AccesUrgenceController {

    private final AccesUrgenceService accesUrgenceService;

    public AccesUrgenceController(AccesUrgenceService accesUrgenceService) {
        this.accesUrgenceService = accesUrgenceService;
    }

    // ------------------------------------------------------------------
    // POST /api/v1/audit/break-the-glass — ouverture de la fenêtre 30 min
    // ------------------------------------------------------------------

    @PostMapping("/break-the-glass")
    public ResponseEntity<AuditDtos.AccesUrgenceResponse> ouvrir(
            @Valid @RequestBody AuditDtos.OuvrirAccesUrgenceRequest request) {
        var acces = accesUrgenceService.ouvrir(request.patientId(), request.reason(),
                utilisateurCourant());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuditDtos.AccesUrgenceResponse.depuis(acces));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/audit/emergency-access — file patient / attente de revue
    // ------------------------------------------------------------------

    @GetMapping("/emergency-access")
    public ResponseEntity<List<AuditDtos.AccesUrgenceDetailResponse>> lire(
            @RequestParam(name = "patientId", required = false) UUID patientId,
            @RequestParam(name = "pending", required = false, defaultValue = "false") boolean pending) {
        var acces = accesUrgenceService.lister(patientId, pending);
        return ResponseEntity.ok(acces.stream()
                .map(AuditDtos.AccesUrgenceDetailResponse::depuis)
                .toList());
    }

    // ------------------------------------------------------------------
    // POST /api/v1/audit/emergency-access/{id}/review — examen a posteriori
    // ------------------------------------------------------------------

    @PostMapping("/emergency-access/{id}/review")
    public ResponseEntity<AuditDtos.AccesUrgenceDetailResponse> revoir(
            @PathVariable UUID id,
            @Valid @RequestBody AuditDtos.RevoirAccesUrgenceRequest request) {
        var acces = accesUrgenceService.revoir(id, request.comment(), utilisateurCourant());
        return ResponseEntity.ok(AuditDtos.AccesUrgenceDetailResponse.depuis(acces));
    }

    // ------------------------------------------------------------------
    // Contexte sécurité : l'auteur depuis le JWT actif (sinon null,
    // documenté — la brèche est portée par l'anonyme en base, V10).
    // ------------------------------------------------------------------

    private UUID utilisateurCourant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            // sub JWT non-UUID : pas d'attribution possible — null (documenté).
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 400/404 locaux (RFC 7807, même style que le GlobalExceptionHandler,
    // sans en dépendre — le module reste autonome)
    // ------------------------------------------------------------------

    @ExceptionHandler(AccesUrgenceIntrouvableException.class)
    public ResponseEntity<ProblemDetail> accesInconnu(AccesUrgenceIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Accès d'urgence inconnu");
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
