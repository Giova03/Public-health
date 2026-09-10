package bf.publichealth.modules.organization.adapter.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.organization.domain.CodeStructureDejaUtiliseException;
import bf.publichealth.modules.organization.domain.IllegalStructureTransitionException;
import bf.publichealth.modules.organization.domain.StructureIntrouvableException;

/**
 * Erreurs du module organization au format problem+json (RFC 7807).
 *
 * <p>Conseil LOCAL au module : il ne mappe QUE les exceptions du
 * domaine organization, et uniquement pour les contrôleurs de ce
 * module (basePackages) — le GlobalExceptionHandler commun reste seul
 * juge du reste. @Order(0) le fait évaluer AVANT le conseil commun :
 * sans cela, son fallback Exception.class transformerait nos
 * 404/409 métier en 500.</p>
 */
@RestControllerAdvice(basePackages = "bf.publichealth.modules.organization")
@Order(0)
public class OrganizationExceptionHandler {

    @ExceptionHandler(StructureIntrouvableException.class)
    public ResponseEntity<ProblemDetail> structureIntrouvable(StructureIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Structure sanitaire introuvable");
        problem.setProperty("structureId", e.getStructureId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(CodeStructureDejaUtiliseException.class)
    public ResponseEntity<ProblemDetail> codeDejaUtilise(CodeStructureDejaUtiliseException e) {
        // 409 : la mnémonique officielle identifie la structure dans tout
        // l'écosystème — l'admin corrige sa saisie sans deviner.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Mnémonique déjà attribuée");
        problem.setProperty("code", e.getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalStructureTransitionException.class)
    public ResponseEntity<ProblemDetail> transitionIllegale(
            IllegalStructureTransitionException e) {
        // Désactiver une inactive, réactiver une active : 409 explicite.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Transition d'activation illégale");
        problem.setProperty("from", e.getFrom());
        problem.setProperty("to", e.getTo());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException e) {
        // Garde de domaine (type inconnu, géolocalisation non appariée) :
        // le domaine est pur, il parle IllegalArgumentException — 400 propre.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMessage());
        problem.setTitle("Requête invalide");
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
    public ResponseEntity<ProblemDetail> parametreIllisible(
            MethodArgumentTypeMismatchException e) {
        // ?active=peut-etre ou ?type=CSPS : 400 propre et local (le
        // conseil commun n'a pas de mappeur pour ce cas → 500 sinon).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Paramètre invalide : " + e.getName());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
