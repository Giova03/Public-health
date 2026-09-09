package bf.publichealth.modules.payments.adapter.fournisseur;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import bf.publichealth.modules.payments.application.EtatPrestataire;
import bf.publichealth.modules.payments.application.FournisseurPaiements;
import bf.publichealth.modules.payments.domain.StatutPrestataire;

/**
 * Fournisseur SIMULÉ (défaut) — déterministe, pour les tests et le dev.
 *
 * <p>Le statut distant d'une référence est semé dans la table
 * {@code payments.provider_simulation} (migration V9) : le test pilote
 * les états distants par INSERT/UPDATE SQL, aucune dépendance réseau.
 * Référence ABSENTE de la table : statut configurable via
 * {@code paiements.reconciliation.fournisseur-simule.sans-entree}
 * (INCONNU par défaut — un run sans semis n'en déduit rien, il examine).</p>
 *
 * <p>Actif uniquement tant qu'aucun {@code fedapay.api-url} n'est
 * configuré (voir la configuration du module) : en production, le
 * fournisseur HTTP réel prend la relève.</p>
 */
public class FournisseurSimule implements FournisseurPaiements {

    private static final Logger LOG = LoggerFactory.getLogger(FournisseurSimule.class);

    private static final Map<String, StatutPrestataire> STATUTS = Map.of(
            "SUCCEEDED", StatutPrestataire.SUCCEEDED,
            "PENDING", StatutPrestataire.PENDING,
            "FAILED", StatutPrestataire.FAILED,
            "CANCELLED", StatutPrestataire.CANCELLED);

    private final JdbcTemplate jdbc;
    private final StatutPrestataire statutSansEntree;

    public FournisseurSimule(JdbcTemplate jdbc, StatutPrestataire statutSansEntree) {
        this.jdbc = jdbc;
        this.statutSansEntree = statutSansEntree;
    }

    @Override
    public EtatPrestataire etatDistant(String reference) {
        if (reference == null || reference.isBlank()) {
            return EtatPrestataire.inconnu();
        }
        List<Map<String, Object>> lignes;
        try {
            lignes = jdbc.queryForList(
                    "SELECT status, amount, provider_tx_id FROM payments.provider_simulation WHERE reference = ?",
                    reference);
        } catch (Exception e) {
            // Le fournisseur ne casse JAMAIS le run : état inconnu.
            LOG.warn("Fournisseur simulé indisponible pour {} : {}", reference, e.getMessage());
            return EtatPrestataire.inconnu();
        }
        if (lignes.isEmpty()) {
            return new EtatPrestataire(statutSansEntree, null, null);
        }
        var ligne = lignes.get(0);
        StatutPrestataire statut = STATUTS.get(String.valueOf(ligne.get("status")));
        BigDecimal montant = ligne.get("amount") == null ? null
                : new BigDecimal(String.valueOf(ligne.get("amount")));
        String txId = ligne.get("provider_tx_id") == null ? null
                : String.valueOf(ligne.get("provider_tx_id"));
        return new EtatPrestataire(statut == null ? StatutPrestataire.INCONNU : statut,
                montant, txId);
    }
}
