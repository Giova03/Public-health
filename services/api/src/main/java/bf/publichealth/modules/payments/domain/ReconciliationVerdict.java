package bf.publichealth.modules.payments.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Verdict de réconciliation — la DÉCISION pure, sans infrastructure.
 *
 * <p>Règle d'or (ADR-006) : la réconciliation nocturne avec le
 * prestataire fait foi, MAIS l'état interne ne recule JAMAIS
 * (forward-only). Pondérations, par priorité décroissante :</p>
 * <ol>
 *   <li><b>État inconnu</b> (prestataire muet, référence inconnue) :
 *       on n'en déduit rien — EXAMINER, écart tracé, aucun mouvement.</li>
 *   <li><b>Fait accompli interne</b> : si l'état interne est DÉJÀ devant
 *       celui du prestataire (AUTHORIZED/SUCCEEDED vs PENDING, SUCCEEDED
 *       vs FAILED/CANCELLED), le fait accompli reste — IGNORER, écart
 *       journalisé, JAMAIS d'exception : un écart n'est pas un incident.</li>
 *   <li><b>Avancée</b> : si le prestataire est devant (ou l'état interne
 *       est en retard), on avance VERS l'état du prestataire par le
 *       CHEMIN LÉGAL le plus court (ex : PENDING→SUCCEEDED se fait
 *       PENDING→AUTHORIZED→SUCCEEDED, l'historique garde chaque pas).</li>
 *   <li><b>Confirmation</b> : états alignés — rien à faire, compteur
 *       « confirmed ».</li>
 * </ol>
 *
 * <p>Matrice (états internes examinés : INITIATED, PENDING, AUTHORIZED,
 * SUCCEEDED — les états scellés FAILED/CANCELLED/REFUNDED/RECONCILED ne
 * sont plus examinés : seul un mouvement vers RECONCILED resterait
 * possible, hors périmètre de ce run) :</p>
 * <pre>
 * interne     | SUCCEEDED       | PENDING               | FAILED        | CANCELLED     | INCONNU
 * ------------+-----------------+-----------------------+---------------+---------------+--------
 * INITIATED   | AVANCER         | AVANCER (→PENDING)    | AVANCER       | AVANCER       | EXAMINER
 * PENDING     | AVANCER         | CONFIRMER             | AVANCER       | AVANCER       | EXAMINER
 * AUTHORIZED  | AVANCER         | IGNORER (rétrograd.)  | AVANCER       | IGNORER       | EXAMINER
 * SUCCEEDED   | CONFIRMER       | IGNORER (rétrograd.)  | IGNORER       | IGNORER       | EXAMINER
 * </pre>
 */
public record ReconciliationVerdict(Action action, PaymentState cible, String natureEcart,
                                    String explication) {

    /** Ce que le run doit faire du paiement examiné. */
    public enum Action {
        /** Avancer (par chemin légal) vers {@link #cible}. */
        AVANCER,
        /** État aligné : rien à faire, compteur confirmed. */
        CONFIRMER,
        /** Forward-only : refus de rétrogradation — écart journalisé, pas d'exception. */
        IGNORER,
        /** Le prestataire n'a rien dit d'exploitable : écart à investiguer, aucun mouvement. */
        EXAMINER
    }

    /** Nature d'écart à tracer dans reconciliation_discrepancy (null si aucun). */
    public static final String ECART_RETROGRADATION = "RETROGRADATION_IGNOREE";
    public static final String ECART_ETAT_INCONNU = "ETAT_INCONNU";

    /**
     * Décide du verdict pour un paiement dont l'état interne est
     * {@code interne} et le statut prestataire {@code externe}.
     */
    public static ReconciliationVerdict pour(PaymentState interne, StatutPrestataire externe) {
        return switch (externe) {
            case INCONNU -> new ReconciliationVerdict(Action.EXAMINER, null, ECART_ETAT_INCONNU,
                    "Statut prestataire inconnu pour cette référence — à examiner, aucun mouvement");
            case SUCCEEDED -> switch (interne) {
                case INITIATED, PENDING, AUTHORIZED -> avancer(interne, PaymentState.SUCCEEDED,
                        "Prestataire SUCCEEDED, interne %s — encaissement à faire avancer".formatted(interne));
                case SUCCEEDED -> new ReconciliationVerdict(Action.CONFIRMER, null, null,
                        "Encaissement confirmé par le prestataire (état interne SUCCEEDED)");
                default -> ignorer(interne, "Prestataire SUCCEEDED mais interne %s scellé — le fait accompli interne reste"
                        .formatted(interne));
            };
            case PENDING -> switch (interne) {
                case INITIATED -> avancer(interne, PaymentState.PENDING,
                        "Prestataire PENDING, interne INITIATED — rattrapage de l'état interne");
                case PENDING -> new ReconciliationVerdict(Action.CONFIRMER, null, null,
                        "PENDING confirmé par le prestataire — en attente, rien à faire");
                default -> ignorer(interne, "Prestataire PENDING mais interne %s — le fait accompli interne reste"
                        .formatted(interne));
            };
            case FAILED -> switch (interne) {
                case INITIATED, PENDING, AUTHORIZED -> avancer(interne, PaymentState.FAILED,
                        "Prestataire FAILED — le paiement échoue");
                default -> ignorer(interne, "Prestataire FAILED mais interne %s — contradiction, le fait accompli interne reste"
                        .formatted(interne));
            };
            case CANCELLED -> switch (interne) {
                case INITIATED, PENDING -> avancer(interne, PaymentState.CANCELLED,
                        "Prestataire CANCELLED — le paiement est annulé côté prestataire");
                default -> ignorer(interne, "Prestataire CANCELLED mais interne %s — contradiction, le fait accompli interne reste"
                        .formatted(interne));
            };
        };
    }

    private static ReconciliationVerdict avancer(PaymentState interne, PaymentState cible, String explication) {
        if (cheminAvant(interne, cible).isEmpty()) {
            // Défensif : aucun chemin légal n'existe — on ne FORCE jamais la machine à états.
            return new ReconciliationVerdict(Action.EXAMINER, null, ECART_ETAT_INCONNU,
                    "Aucun chemin légal de %s vers %s — à examiner".formatted(interne, cible));
        }
        return new ReconciliationVerdict(Action.AVANCER, cible, null, explication);
    }

    private static ReconciliationVerdict ignorer(PaymentState interne, String explication) {
        return new ReconciliationVerdict(Action.IGNORER, null, ECART_RETROGRADATION, explication);
    }

    /**
     * Chemin avant LÉGAL le plus court (BFS sur la machine à états) de
     * {@code depart} vers {@code cible} : séquence des étapes À FRANCHIR
     * (le départ exclu, la cible incluse). Liste vide si la cible est
     * déjà atteinte ou inaccessible — jamais de raccourci illégal :
     * PENDING→SUCCEEDED se fait PENDING→AUTHORIZED→SUCCEEDED.
     */
    public static List<PaymentState> cheminAvant(PaymentState depart, PaymentState cible) {
        if (depart == cible) {
            return List.of();
        }
        Map<PaymentState, PaymentState> precedent = new EnumMap<>(PaymentState.class);
        Deque<PaymentState> file = new ArrayDeque<>();
        file.add(depart);
        while (!file.isEmpty()) {
            PaymentState courant = file.poll();
            for (PaymentState suivant : PaymentState.values()) {
                if (courant != suivant && courant.canTransitionTo(suivant)
                        && !precedent.containsKey(suivant) && suivant != depart) {
                    precedent.put(suivant, courant);
                    if (suivant == cible) {
                        return reconstituerChemin(precedent, cible);
                    }
                    file.add(suivant);
                }
            }
        }
        return List.of();
    }

    private static List<PaymentState> reconstituerChemin(Map<PaymentState, PaymentState> precedent,
                                                         PaymentState cible) {
        List<PaymentState> chemin = new ArrayList<>();
        PaymentState etat = cible;
        while (etat != null) {
            chemin.add(0, etat);
            etat = precedent.get(etat);
        }
        // Le départ est exclu de la séquence à franchir.
        chemin.removeFirst();
        return List.copyOf(chemin);
    }
}
