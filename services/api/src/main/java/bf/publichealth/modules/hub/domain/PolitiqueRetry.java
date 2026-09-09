package bf.publichealth.modules.hub.domain;

/**
 * Politique de retransmission — le verdict après chaque réponse du
 * transport. Pures règles, testables sans infrastructure.
 *
 * <p>Ordre d'évaluation (protocole HUB, ADR non négociable) :
 * <ol>
 *   <li>acquittement → {@link Verdict#ACQUITTER} : le message passe
 *       {@code acked} et le filigrane de la destination avance ;</li>
 *   <li>échec DÉFINITIF → {@link Verdict#LETTER_MORTE} immédiate :
 *       retransmettre ne servira plus rien ;</li>
 *   <li>échec transitoire → retransmission SAUF si le nombre de
 *       tentatives épuisées atteint le maximum (défaut {@value
 *       #TENTATIVES_MAX_DEFAUT}) : alors lettre morte (DLQ).</li>
 * </ol></p>
 */
public final class PolitiqueRetry {

    /** Nombre de tentatives maximales avant lettre morte (DLQ). */
    public static final int TENTATIVES_MAX_DEFAUT = 8;

    /** Verdict après réponse du transport. */
    public enum Verdict {
        /** Acquitté : statut {@code acked}, filigrane avancé. */
        ACQUITTER,
        /** Échec transitoire : retransmission avec trempe et gigue. */
        RETENTER,
        /** Lettre morte (DLQ) : plus aucune retransmission automatique. */
        LETTER_MORTE
    }

    private final int tentativesMax;

    /** Politique du protocole : 8 tentatives avant lettre morte. */
    public PolitiqueRetry() {
        this(TENTATIVES_MAX_DEFAUT);
    }

    /** Politique pilotée (les tests abaissent la limite). */
    public PolitiqueRetry(int tentativesMax) {
        if (tentativesMax < 1) {
            throw new IllegalArgumentException(
                    "Le maximum de tentatives doit être ≥ 1 : " + tentativesMax);
        }
        this.tentativesMax = tentativesMax;
    }

    /**
     * Rend le verdict pour une réponse.
     *
     * @param reponse   la réponse du transport
     * @param tentativesEffectuees nombre de tentatives ENVOYÉES pour ce
     *                  message, celle qui vient de répondre comprise
     *                  (premier envoi = 1 ; retransmissions = 2, 3…)
     */
    public Verdict verdict(ReponseTransport reponse, int tentativesEffectuees) {
        if (reponse == null) {
            throw new IllegalArgumentException("Réponse de transport manquante");
        }
        if (tentativesEffectuees < 1) {
            throw new IllegalArgumentException(
                    "Au moins une tentative vient d'être effectuée : " + tentativesEffectuees);
        }
        if (reponse.acquitte()) {
            return Verdict.ACQUITTER;
        }
        if (reponse.definitif()) {
            return Verdict.LETTER_MORTE;
        }
        return tentativesEffectuees >= tentativesMax ? Verdict.LETTER_MORTE : Verdict.RETENTER;
    }

    /** Maximum de tentatives avant lettre morte. */
    public int tentativesMax() {
        return tentativesMax;
    }
}
