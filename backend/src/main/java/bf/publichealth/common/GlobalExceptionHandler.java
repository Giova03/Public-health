package bf.publichealth.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bf.publichealth.modules.administration.domain.RolesPermissions;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.identity.domain.PatientDuplicateException;
import bf.publichealth.modules.identity.domain.PatientMergedException;
import bf.publichealth.modules.payments.domain.IllegalPaymentTransitionException;
import bf.publichealth.modules.payments.domain.WebhookSignatureInvalidException;

/**
 * Erreurs au format problem+json (RFC 7807) : type, title, status, detail.
 * Aucune pile interne ne fuit — le traceId est porté par les logs structurés.
 *
 * <p>Suggestion 4 de l'audit (Q42) : le 409 doublons n'embarque les
 * candidats QUE pour un appelant portant {@code patient:lire} — anonyme,
 * patient ou rôle insuffisant reçoivent un 409 MASQUÉ (compteur sans
 * identité). La réponse reste un 409 (le contrat « décider humainement »
 * n'est pas cassé), seule la donnée sensible est conditionnée.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditRecorder auditRecorder;

    public GlobalExceptionHandler(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @ExceptionHandler(IllegalPaymentTransitionException.class)
    public ResponseEntity<ProblemDetail> illegalTransition(IllegalPaymentTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Transition de paiement illégale");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(WebhookSignatureInvalidException.class)
    public ResponseEntity<ProblemDetail> webhookSignature(WebhookSignatureInvalidException e) {
        // 401 générique : on ne donne rien d'exploitable à un falsificateur.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Webhook rejeté");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(PatientDuplicateException.class)
    public ResponseEntity<ProblemDetail> patientDuplicate(PatientDuplicateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Patient probablement déjà enregistré");

        // Suggestion 4 / Q42 — aveuglement du 409 :
        // les candidats (identité, naissance, référence) ne voyagent QUE
        // pour un opérateur portant patient:lire. Tout le reste — anonyme
        // (posture ouverte ou override SECURITE_JWT_ACTIF=false), jeton
        // patient, rôle inconnu — reçoit le COMPTEUR sans les dossiers.
        // Défense en profondeur : même si le filtre RBAC est contourné ou
        // mal configuré, la donnée ne sort pas d'ici.
        if (ContexteAppelant.permission(RolesPermissions.PATIENT_LIRE)) {
            // 409 = contrat UX (ADR-003) pour un opérateur identifié :
            // les candidats VOYAGENT dans la réponse, l'agent décide
            // humainement — jamais de fusion ni création en silence.
            problem.setProperty("candidates", e.getCandidates());
        } else {
            problem.setProperty("candidatesRedacted", true);
            problem.setProperty("candidatesCount", e.getCandidates().size());
            auditRecorder.record(ContexteAppelant.acteur(), "PATIENT_DUPLICATE_REDACTED",
                    "patient", null, null,
                    "CANDIDATS_MASQUES_APPELANT_SANS_PATIENT_LIRE",
                    AuditEntryEntity.Result.DENIED,
                    java.util.Map.of("candidates", e.getCandidates().size(),
                            "appelant", String.valueOf(ContexteAppelant.role())));
        }
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(PatientMergedException.class)
    public ResponseEntity<ProblemDetail> patientMerged(PatientMergedException e) {
        // 410 : le dossier a fusionné — le consommateur suit le maître.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
        problem.setTitle("Dossier fusionné");
        problem.setProperty("masterId", e.getMasterId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "En-tête obligatoire manquant : " + e.getHeaderName());
        problem.setTitle("Requête incomplète");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Charge invalide : " + e.getMessage());
        problem.setTitle("Validation en échec");
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        UUID traceId = UUID.randomUUID();
        LOG.error("Erreur interne traceId={} : {}", traceId, e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Erreur interne — traceId " + traceId);
        problem.setTitle("Erreur interne");
        return ResponseEntity.of(problem).build();
    }
}
