package bf.publichealth.modules.administration.domain;

/**
 * Transition de statut d'utilisateur illégale — 409 au niveau HTTP.
 *
 * <p>Suspendre un invité (jamais activé), activer un suspendu (il faut
 * réactiver), réactiver un actif : le refus est explicite, tracé
 * DENIED en audit — jamais de double changement en silence.
 * {@code from}/{@code to} portent les codes de statut (clés sans
 * accent dans le ProblemDetail, patron payments).</p>
 */
public class IllegalUtilisateurTransitionException extends RuntimeException {

    private final StatutUtilisateur from;
    private final StatutUtilisateur to;

    public IllegalUtilisateurTransitionException(StatutUtilisateur from, StatutUtilisateur to) {
        super("Transition d'utilisateur illégale : " + from.getCode() + " → " + to.getCode()
                + " (légal : invite→actif par activation, actif→suspendu par suspension, "
                + "suspendu→actif par réactivation)");
        this.from = from;
        this.to = to;
    }

    public StatutUtilisateur getFrom() {
        return from;
    }

    public StatutUtilisateur getTo() {
        return to;
    }
}
