package bf.publichealth.modules.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calcul du total d'une facture depuis ses lignes — le domaine est le
 * SEUL calculateur de montant (le client propose des lignes, jamais un
 * total : un total accepté du client serait une falsification en attente).
 *
 * <p>Règle d'arrondi, explicite et commerciale : chaque ligne est
 * arrondie à 2 décimales en HALF_UP ({@code quantite × prixUnitaire}),
 * puis les lignes sont sommées. Le total final est toujours en 2
 * décimales. Quantité et prix unitaire sont contrôlés ({@code quantity > 0},
 * {@code unit_price >= 0}) — la cohérence avec les CHECK V9 est
 * doublement garantie (domaine + base).</p>
 */
public final class InvoiceTotals {

    /** Montant d'une ligne : quantité × prix unitaire, arrondi 2 décimales. */
    public static final int ECHELLE = 2;

    private InvoiceTotals() {
    }

    /**
     * Ligne de facture en formulation domaine — le label est la
     * description facturée (acte, produit, forfait), jamais une donnée
     * sensible du patient.
     */
    public record LigneFacture(String libelle, BigDecimal quantite, BigDecimal prixUnitaire) {
    }

    /** Contrôles de lignes : au moins une ligne, libellé présent, quantité et prix cohérents. */
    public static void validerLignes(List<LigneFacture> lignes) {
        if (lignes == null || lignes.isEmpty()) {
            throw new IllegalArgumentException("Une facture porte au moins une ligne");
        }
        for (LigneFacture ligne : lignes) {
            if (ligne == null
                    || ligne.libelle() == null || ligne.libelle().isBlank()
                    || ligne.quantite() == null || ligne.prixUnitaire() == null) {
                throw new IllegalArgumentException("Chaque ligne porte un libellé, une quantité et un prix unitaire");
            }
            if (ligne.quantite().signum() <= 0) {
                throw new IllegalArgumentException("La quantité d'une ligne est strictement positive : "
                        + ligne.quantite());
            }
            if (ligne.quantite().precision() - ligne.quantite().scale() > 10
                    || ligne.prixUnitaire().precision() - ligne.prixUnitaire().scale() > 10) {
                throw new IllegalArgumentException("Quantité et prix unitaire tiennent en 12 chiffres, 2 décimales");
            }
            if (ligne.prixUnitaire().signum() < 0) {
                throw new IllegalArgumentException("Le prix unitaire d'une ligne est positif ou nul : "
                        + ligne.prixUnitaire());
            }
        }
    }

    /** Montant d'une ligne : quantité × prix, arrondi commercial 2 décimales. */
    public static BigDecimal montantLigne(LigneFacture ligne) {
        return arrondir(ligne.quantite().multiply(ligne.prixUnitaire()));
    }

    /** Total de la facture : somme des montants de lignes arrondis, en 2 décimales. */
    public static BigDecimal total(List<LigneFacture> lignes) {
        validerLignes(lignes);
        BigDecimal total = BigDecimal.ZERO;
        for (LigneFacture ligne : lignes) {
            total = total.add(montantLigne(ligne));
        }
        return arrondir(total);
    }

    private static BigDecimal arrondir(BigDecimal valeur) {
        return valeur.setScale(ECHELLE, RoundingMode.HALF_UP);
    }
}
