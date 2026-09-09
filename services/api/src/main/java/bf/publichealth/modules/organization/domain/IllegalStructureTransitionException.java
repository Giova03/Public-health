package bf.publichealth.modules.organization.domain;

/**
 * Transition d'état d'activation illégale — 409 au niveau HTTP.
 *
 * <p>Désactiver une structure déjà inactive, réactiver une structure
 * déjà active : l'état demandé est celui déjà en place, le refus est
 * explicite (jamais de double changement en silence). {@code from}/{@code to}
 * portent les codes d'état {@code active}/{@code inactive} (clés sans
 * accent dans le ProblemDetail, patron payments).</p>
 */
public class IllegalStructureTransitionException extends RuntimeException {

    private final String from;
    private final String to;

    public IllegalStructureTransitionException(String from, String to, String detail) {
        super(detail);
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
