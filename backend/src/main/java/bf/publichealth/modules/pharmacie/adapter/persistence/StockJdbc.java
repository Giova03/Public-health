package bf.publichealth.modules.pharmacie.adapter.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance du stock — JDBC sur pharmacie.stock_item / pharmacie.mouvement
 * (V14). Les mouvements sont append-only (trigger SQL) ; le solde vit
 * sur stock_item.quantite, décrémenté/incrémenté DANS la même transaction
 * que la dispensation (I8 : on ne peut plus dispenser ce qui n'existe pas).
 */
@Repository
public class StockJdbc {

    private final JdbcTemplate jdbc;

    public StockJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StockItem(UUID id, UUID structureId, String medicationCode,
                            String medicationLabel, BigDecimal quantite, int seuilAlerte,
                            Instant updatedAt) {
    }

    public record Mouvement(UUID id, UUID stockItemId, String type, BigDecimal quantite,
                            UUID referenceId, String motif, Instant createdAt, UUID createdBy,
                            String medicationCode) {
    }

    @Transactional(readOnly = true)
    public List<StockItem> listerParStructure(UUID structureId) {
        return jdbc.query("""
                SELECT id, structure_id, medication_code, medication_label, quantite,
                       seuil_alerte, updated_at
                  FROM pharmacie.stock_item
                 WHERE structure_id = ?
                 ORDER BY medication_label
                """, this::mapperItem, structureId);
    }

    @Transactional(readOnly = true)
    public List<Mouvement> mouvements(UUID structureId, int limite) {
        return jdbc.query("""
                SELECT m.id, m.stock_item_id, m.type, m.quantite, m.reference_id, m.motif,
                       m.created_at, m.created_by, s.medication_code
                  FROM pharmacie.mouvement m
                  JOIN pharmacie.stock_item s ON s.id = m.stock_item_id
                 WHERE s.structure_id = ?
                 ORDER BY m.created_at DESC
                 LIMIT ?
                """, this::mapperMouvement, structureId, limite);
    }

    /** Ligne de stock pour (structure, médicament) — vide = couverture progressive. */
    @Transactional(readOnly = true)
    public Optional<StockItem> trouverLigne(UUID structureId, String medicationCode) {
        return jdbc.query("""
                SELECT id, structure_id, medication_code, medication_label, quantite,
                       seuil_alerte, updated_at
                  FROM pharmacie.stock_item
                 WHERE structure_id = ? AND medication_code = ?
                """, this::mapperItem, structureId, medicationCode).stream().findFirst();
    }

    /** Crée la ligne si absente (réception initiale). */
    @Transactional
    public StockItem upsert(UUID structureId, String medicationCode, String medicationLabel,
                            int seuilAlerte) {
        jdbc.update("""
                INSERT INTO pharmacie.stock_item
                    (id, structure_id, medication_code, medication_label, quantite, seuil_alerte)
                VALUES (?, ?, ?, ?, 0, ?)
                ON CONFLICT (structure_id, medication_code) DO NOTHING
                """, UUID.randomUUID(), structureId, medicationCode, medicationLabel, seuilAlerte);
        return trouverLigne(structureId, medicationCode).orElseThrow();
    }

    /**
     * Décrémente le solde (dispensation). RETOURNE le nouveau solde —
     * l'appelant a DÉJÀ vérifié la disponibilité.
     */
    @Transactional
    public BigDecimal consommer(UUID stockItemId, BigDecimal quantite) {
        jdbc.update("""
                UPDATE pharmacie.stock_item
                   SET quantite = quantite - ?, updated_at = now()
                 WHERE id = ?
                """, quantite, stockItemId);
        return jdbc.queryForObject(
                "SELECT quantite FROM pharmacie.stock_item WHERE id = ?",
                BigDecimal.class, stockItemId);
    }

    /** Incrémente le solde (réception, contre-entrée). */
    @Transactional
    public BigDecimal approvisionner(UUID stockItemId, BigDecimal quantite) {
        jdbc.update("""
                UPDATE pharmacie.stock_item
                   SET quantite = quantite + ?, updated_at = now()
                 WHERE id = ?
                """, quantite, stockItemId);
        return jdbc.queryForObject(
                "SELECT quantite FROM pharmacie.stock_item WHERE id = ?",
                BigDecimal.class, stockItemId);
    }

    /** Ajustement d'inventaire : pose le solde EXACT (écart compté). */
    @Transactional
    public void ajuster(UUID stockItemId, BigDecimal soldeCompte) {
        if (soldeCompte.signum() < 0) {
            throw new IllegalArgumentException("Un solde d'inventaire ne peut pas être négatif");
        }
        jdbc.update("""
                UPDATE pharmacie.stock_item
                   SET quantite = ?, updated_at = now()
                 WHERE id = ?
                """, soldeCompte, stockItemId);
    }

    /** Mouvement append-only — la traçabilité complète des entrées/sorties. */
    @Transactional
    public void enregistrerMouvement(UUID stockItemId, String type, BigDecimal quantite,
                                     UUID referenceId, String motif, UUID createdBy) {
        jdbc.update("""
                INSERT INTO pharmacie.mouvement
                    (id, stock_item_id, type, quantite, reference_id, motif, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), stockItemId, type, quantite, referenceId,
                motif, createdBy);
    }

    private StockItem mapperItem(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new StockItem(
                rs.getObject("id", UUID.class),
                rs.getObject("structure_id", UUID.class),
                rs.getString("medication_code"),
                rs.getString("medication_label"),
                rs.getBigDecimal("quantite"),
                rs.getInt("seuil_alerte"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Mouvement mapperMouvement(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Mouvement(
                rs.getObject("id", UUID.class),
                rs.getObject("stock_item_id", UUID.class),
                rs.getString("type"),
                rs.getBigDecimal("quantite"),
                rs.getObject("reference_id", UUID.class),
                rs.getString("motif"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getString("medication_code"));
    }
}
