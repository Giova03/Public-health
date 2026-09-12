package bf.publichealth.modules.pharmacie.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.pharmacie.adapter.persistence.StockJdbc;

/**
 * Service stock (V14, I8) — la dispensation adossée au stock réel.
 *
 * <p>Règle : si une ligne de stock existe pour (structure, médicament),
 * toute dispensation est VÉRIFIÉE contre le solde — insuffisant →
 * RUPTURE (409) ; suffisante → décrément + mouvement DISPENSATION dans
 * LA MÊME TRANSACTION. Si aucune ligne n'existe, la dispensation passe
 * avec audit (couverture progressive des référentiels — documenté).</p>
 */
@Service
public class StockService {

    private final StockJdbc stock;
    private final AuditRecorder audit;

    public StockService(StockJdbc stock, AuditRecorder audit) {
        this.stock = stock;
        this.audit = audit;
    }

    /** La rupture de stock n'est plus une fiction : elle REFUSE la dispensation. */
    public static class RuptureStockException extends RuntimeException {
        private final BigDecimal disponible;

        public RuptureStockException(String message, BigDecimal disponible) {
            super(message);
            this.disponible = disponible;
        }

        public BigDecimal getDisponible() {
            return disponible;
        }
    }

    /**
     * Consomme le stock pour une dispensation. Appelé par
     * PrescriptionService DANS sa transaction : la rupture fait tout
     * rouler en arrière, y compris la dispensation.
     */
    @Transactional
    public void consommerPourDispensation(UUID structureId, String medicationCode,
                                          String medicationLabel, BigDecimal quantite,
                                          UUID prescriptionId, UUID acteur) {
        Optional<StockJdbc.StockItem> ligne = stock.trouverLigne(structureId, medicationCode);
        if (ligne.isEmpty()) {
            audit.record(acteur, "STOCK_ABSENT", "stock", null, structureId,
                    "aucune ligne de stock pour " + medicationCode,
                    AuditEntryEntity.Result.SUCCESS,
                    java.util.Map.of("prescriptionId", String.valueOf(prescriptionId)));
            return; // couverture progressive — documenté
        }
        StockJdbc.StockItem item = ligne.get();
        if (item.quantite().compareTo(quantite) < 0) {
            audit.record(acteur, "DISPENSATION_DENIED", "stock", item.id(), structureId,
                    "RUPTURE_STOCK", AuditEntryEntity.Result.DENIED,
                    java.util.Map.of("disponible", item.quantite(), "demande", quantite,
                            "medication", medicationCode));
            throw new RuptureStockException(
                    "Rupture de stock : " + medicationLabel + " — disponible "
                            + item.quantite().stripTrailingZeros().toPlainString()
                            + ", demandé " + quantite.stripTrailingZeros().toPlainString(),
                    item.quantite());
        }
        BigDecimal solde = stock.consommer(item.id(), quantite);
        stock.enregistrerMouvement(item.id(), "dispensation", quantite, prescriptionId,
                "dispensation", acteur);
        if (solde.compareTo(BigDecimal.valueOf(item.seuilAlerte())) <= 0) {
            audit.record(acteur, "STOCK_ALERTE_SEUIL", "stock", item.id(), structureId,
                    medicationCode + " sous le seuil (" + solde + ")",
                    AuditEntryEntity.Result.SUCCESS, null);
        }
    }

    /** Contre-entrée : le médicament REVIENT au stock (mouvement traçé). */
    @Transactional
    public void retourContreEntree(UUID structureId, String medicationCode,
                                   BigDecimal quantite, UUID referenceId, UUID acteur) {
        Optional<StockJdbc.StockItem> ligne = stock.trouverLigne(structureId, medicationCode);
        if (ligne.isEmpty()) {
            return;
        }
        stock.approvisionner(ligne.get().id(), quantite);
        stock.enregistrerMouvement(ligne.get().id(), "contre_entree", quantite, referenceId,
                "retour au stock", acteur);
    }

    /** Réception de fourniture (CAMEG/CSD) : ligne créée si besoin + solde augmenté. */
    @Transactional
    public StockJdbc.StockItem reception(UUID structureId, String medicationCode,
                                         String medicationLabel, BigDecimal quantite,
                                         String motif, UUID acteur) {
        if (quantite.signum() <= 0) {
            throw new IllegalArgumentException("La quantité reçue doit être positive");
        }
        StockJdbc.StockItem item = stock.upsert(structureId, medicationCode,
                medicationLabel == null ? medicationCode : medicationLabel, 10);
        stock.approvisionner(item.id(), quantite);
        stock.enregistrerMouvement(item.id(), "reception", quantite, null, motif, acteur);
        audit.record(acteur, "STOCK_RECEPTION", "stock", item.id(), structureId,
                medicationCode + " +" + quantite, AuditEntryEntity.Result.SUCCESS, null);
        return stock.trouverLigne(structureId, medicationCode).orElseThrow();
    }

    /** Ajustement d'inventaire mensuel (COCOM) : solde exact posé, écart tracé. */
    @Transactional
    public StockJdbc.StockItem inventaire(UUID structureId, String medicationCode,
                                          BigDecimal soldeCompte, String motif, UUID acteur) {
        StockJdbc.StockItem item = stock.upsert(structureId, medicationCode, medicationCode, 10);
        BigDecimal ecart = soldeCompte.subtract(item.quantite());
        stock.ajuster(item.id(), soldeCompte);
        stock.enregistrerMouvement(item.id(), "ajustement",
                ecart.abs().signum() == 0 ? BigDecimal.ONE : ecart.abs(), null,
                motif + " (écart " + ecart.stripTrailingZeros().toPlainString() + ")", acteur);
        audit.record(acteur, "STOCK_INVENTAIRE", "stock", item.id(), structureId,
                motif, AuditEntryEntity.Result.SUCCESS,
                java.util.Map.of("ecart", ecart));
        return stock.trouverLigne(structureId, medicationCode).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<StockJdbc.StockItem> parStructure(UUID structureId) {
        return stock.listerParStructure(structureId);
    }

    @Transactional(readOnly = true)
    public List<StockJdbc.Mouvement> mouvements(UUID structureId, int limite) {
        return stock.mouvements(structureId, limite);
    }
}
