package bf.publichealth.modules.administration.adapter.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import bf.publichealth.modules.administration.domain.CompteDejaLierException;
import bf.publichealth.modules.administration.domain.EmailDejaUtiliseException;
import bf.publichealth.modules.administration.domain.IllegalUtilisateurTransitionException;
import bf.publichealth.modules.administration.domain.StructureInconnueException;
import bf.publichealth.modules.administration.domain.UtilisateurIntrouvableException;

/**
 * Erreurs du module administration au format problem+json (RFC 7807).
 *
 * <p>Conseil LOCAL au module : il ne mappe QUE les exceptions du
 * domaine administration, et uniquement pour les contrôleurs de ce
 * module (basePackages) — le GlobalExceptionHandler commun reste seul
 * juge du reste. @Order(0) le fait évaluer AVANT le conseil commun :
 * sans cela, son fallback Exception.class transformerait nos
 * 404/409 métier en 500.</p>
 */
@RestControllerAdvice(basePackages = "bf.publichealth.modules.administration")
@Order(0)
public class AdministrationExceptionHandler {

    @ExceptionHandler(UtilisateurIntrouvableException.class)
    public ResponseEntity<ProblemDetail> utilisateurIntrouvable(
            UtilisateurIntrouvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Utilisateur introuvable");
        problem.setProperty("utilisateurId", e.getUtilisateurId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(EmailDejaUtiliseException.class)
    public ResponseEntity<ProblemDetail> emailDejaUtilise(EmailDejaUtiliseException e) {
        // 409 = idempotence par email : le statut du compte existant
        // voyage dans la réponse, l'admin décide (réactiver un suspendu,
        // attendre l'invitation en cours) — jamais de second compte.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Email déjà utilisé");
        problem.setProperty("email", e.getEmail());
        problem.setProperty("statutExistant", e.getStatutExistant());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(CompteDejaLierException.class)
    public ResponseEntity<ProblemDetail> compteDejaLie(CompteDejaLierException e) {
        // 409 : la liaison Supabase est définitive — le porteur actuel
        // voyage dans la réponse, l'admin corrige sans deviner.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Compte Supabase déjà lié");
        problem.setProperty("supabaseUserId", e.getSupabaseUserId());
        problem.setProperty("utilisateurPorteur", e.getUtilisateurPorteur());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalUtilisateurTransitionException.class)
    public ResponseEntity<ProblemDetail> transitionIllegale(
            IllegalUtilisateurTransitionException e) {
        // Suspendre un invité, activer un suspendu, réactiver un
        // actif : 409 explicite + trace DENIED en audit.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage());
        problem.setTitle("Transition d'utilisateur illégale");
        problem.setProperty("from", e.getFrom().getCode());
        problem.setProperty("to", e.getTo().getCode());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(StructureInconnueException.class)
    public ResponseEntity<ProblemDetail> structureInconnue(StructureInconnueException e) {
        // structure_id est une référence logique (loi n°3) : sans
        // structure connue dans l'annuaire, l'invitation est refusée.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage());
        problem.setTitle("Structure de rattachement inconnue");
        problem.setProperty("structureId", e.getStructureId());
        return ResponseEntity.of(problem).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException e) {
        // Garde de domaine (rôle inconnu, motif absent) : le domaine
        // est pur, il parle IllegalArgumentException — 400 propre.
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
        // ?status=bloque ou ?role=chirurgien : 400 propre et local (le
        // conseil commun n'a pas de mappeur pour ce cas → 500 sinon).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Paramètre invalide : " + e.getName());
        problem.setTitle("Requête invalide");
        return ResponseEntity.of(problem).build();
    }
}
