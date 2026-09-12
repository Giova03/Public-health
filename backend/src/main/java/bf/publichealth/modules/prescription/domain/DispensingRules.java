package bf.publichealth.modules.prescription.domain;

import java.math.BigDecimal;

/**
 * Règles de dispensation — pur domaine, testable sans infrastructure.
 *
 * <p>La dispensation est PARTIELLE par conception (réalité des centres de
 * santé du Burkina Faso : ruptures de stock, retraits fractionnés) :
 * plusieurs délivrances se CUMULENT par ligne jusqu'à épuisement de la
 * quantité prescrite — jamais au-delà. Le cumul fait foi, chaque
 * dispensation est un fait accompli append-only.</p>
 */
public final class DispensingRules {

    private DispensingRules() {
    }

    /**
     * Quantité encore dispensable pour une ligne :
     * quantité prescrite − déjà délivrée (cumul des dispensations).
     */
    public static BigDecimal restant(BigDecimal quantitePrescrite, BigDecimal dejaDispense) {
        BigDecimal dispense = dejaDispense == null ? BigDecimal.ZERO : dejaDispense;
        return quantitePrescrite.subtract(dispense);
    }

    /**
     * Vérifie UNE dispensation avant insertion. Refuse, dans l'ordre :
     * <ol>
     *   <li>quantité non strictement positive ;</li>
     *   <li>prescription non active ({@link PrescriptionInactiveException}) ;</li>
     *   <li>dépassement du restant ({@link QuantityExceededException},
     *       qui porte le détail : restant et demandé).</li>
     * </ol>
     */
    public static void verifierAvantDispensation(StatutPrescription statut,
                                                 BigDecimal quantitePrescrite,
                                                 BigDecimal dejaDispense,
                                                 BigDecimal quantiteDemandee) {
        if (quantiteDemandee == null || quantiteDemandee.signum() <= 0) {
            throw new IllegalArgumentException(
                    "La quantité à dispenser doit être strictement positive");
        }
        if (!statut.estActive()) {
            throw new PrescriptionInactiveException(statut);
        }
        BigDecimal restant = restant(quantitePrescrite, dejaDispense);
        if (quantiteDemandee.compareTo(restant) > 0) {
            throw new QuantityExceededException(restant, quantiteDemandee);
        }
    }
}
