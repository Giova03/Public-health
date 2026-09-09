package bf.publichealth.modules.prescription.domain;

import java.math.BigDecimal;

/**
 * Dépassement de la quantité dispensable : la somme cumulée des
 * dispensations ne peut jamais excéder la quantité prescrite.
 *
 * <p>Porte le détail exigé par le contrat d'API : le restant exact et la
 * quantité demandée — l'API les embarque dans le 409 (problem+json,
 * RFC 7807) pour que le comptoir corrige sa saisie sans nouveau
 * aller-retour.</p>
 */
public class QuantityExceededException extends RuntimeException {

    private final BigDecimal restant;
    private final BigDecimal demandee;

    public QuantityExceededException(BigDecimal restant, BigDecimal demandee) {
        super("Dépassement de la quantité prescrite : restant %s, demandé %s"
                .formatted(restant, demandee));
        this.restant = restant;
        this.demandee = demandee;
    }

    /** Quantité encore dispensable au moment du refus. */
    public BigDecimal getRestant() {
        return restant;
    }

    /** Quantité que le demandeur a tenté de dispenser. */
    public BigDecimal getDemandee() {
        return demandee;
    }
}
