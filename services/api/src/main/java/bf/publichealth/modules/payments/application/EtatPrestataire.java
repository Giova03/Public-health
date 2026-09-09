package bf.publichealth.modules.payments.application;

import java.math.BigDecimal;

import bf.publichealth.modules.payments.domain.StatutPrestataire;

/**
 * État d'une transaction VUE PAR LE PRESTATAIRE (réponse du port
 * {@link FournisseurPaiements}).
 *
 * @param statut                statut prestataire (JAMAIS null ; INCONNU si muet)
 * @param montant               montant prestataire si connu (nullable)
 * @param idTransactionPrestataire identifiant technique prestataire (nullable)
 */
public record EtatPrestataire(StatutPrestataire statut, BigDecimal montant,
                              String idTransactionPrestataire) {

    public EtatPrestataire {
        if (statut == null) {
            throw new IllegalArgumentException("Le statut prestataire est obligatoire (INCONNU si muet)");
        }
    }

    /** Réponse du prestataire muet ou en erreur : on n'en déduit rien. */
    public static EtatPrestataire inconnu() {
        return new EtatPrestataire(StatutPrestataire.INCONNU, null, null);
    }
}
