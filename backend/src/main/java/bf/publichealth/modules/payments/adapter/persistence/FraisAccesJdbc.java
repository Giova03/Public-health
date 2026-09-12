package bf.publichealth.modules.payments.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des frais d'accès — JDBC pur sur payments.frais_acces (V15).
 *
 * <p>Le ticket d'accès du jour (patient × structure × jour UTC) : la
 * machine forward-only en_attente → paye | exonere est défendue CÔTÉ
 * SERVICE (transitions légales explicites), l'unicité par jour est
 * défendue CÔTÉ BASE (index unique fonctionnel — la course concurrente
 * de deux caissiers ne crée jamais deux tickets).</p>
 */
@Repository
public class FraisAccesJdbc {

    private final JdbcTemplate jdbc;

    public FraisAccesJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Un ticket d'accès lu (assemblage complet, exonération incluse). */
    public record FraisAccesLue(UUID id, UUID patientId, UUID structureId,
                                String statut, long montantXof,
                                String exonerationNature, String exonerationMotif,
                                UUID exonerationDecideePar,
                                UUID encaissePar, Instant encaisseLe,
                                UUID createdBy, Instant createdAt) {
    }

    private static final String COLONNES = """
            id, patient_id, structure_id, statut, montant_xof,
            exoneration_nature, exoneration_motif, exoneration_decidee_par,
            encaisse_par, encaisse_le, created_by, created_at
            """;

    @Transactional
    public void inserer(UUID id, UUID patientId, UUID structureId, long montantXof,
                        UUID createdBy, Instant date) {
        jdbc.update("""
                INSERT INTO payments.frais_acces
                    (id, patient_id, structure_id, statut, montant_xof, created_by, created_at)
                VALUES (?, ?, ?, 'en_attente', ?, ?, ?)
                """,
                id, patientId, structureId, montantXof, createdBy, Timestamp.from(date));
    }

    /** Le ticket du jour (UTC) pour ce patient dans cette structure. */
    @Transactional(readOnly = true)
    public Optional<FraisAccesLue> duJour(UUID patientId, UUID structureId) {
        List<FraisAccesLue> tickets = lister("""
                WHERE patient_id = ? AND structure_id = ?
                  AND (created_at AT TIME ZONE 'UTC')::date = (now() AT TIME ZONE 'UTC')::date
                ORDER BY created_at DESC LIMIT 1
                """, patientId, structureId);
        return tickets.isEmpty() ? Optional.empty() : Optional.of(tickets.get(0));
    }

    @Transactional(readOnly = true)
    public Optional<FraisAccesLue> trouver(UUID id) {
        List<FraisAccesLue> tickets = lister("WHERE id = ?", id);
        return tickets.isEmpty() ? Optional.empty() : Optional.of(tickets.get(0));
    }

    /** Historique filtré — patientId OBLIGATOIRE (périmètre), statut/structure optionnels. */
    @Transactional(readOnly = true)
    public List<FraisAccesLue> lister(UUID patientId, UUID structureId, String statut) {
        StringBuilder filtre = new StringBuilder("WHERE patient_id = ?");
        if (structureId != null) {
            filtre.append(" AND structure_id = ?");
        }
        if (statut != null && !statut.isBlank()) {
            filtre.append(" AND statut = ?");
        }
        filtre.append(" ORDER BY created_at DESC LIMIT 100");
        Object[] parametres = structureId != null && statut != null && !statut.isBlank()
                ? new Object[] { patientId, structureId, statut }
                : structureId != null
                        ? new Object[] { patientId, structureId }
                        : statut != null && !statut.isBlank()
                                ? new Object[] { patientId, statut }
                                : new Object[] { patientId };
        return lister(filtre.toString(), parametres);
    }

    /** La file d'attente de la caisse : tickets EN_ATTENTE de la structure. */
    @Transactional(readOnly = true)
    public List<FraisAccesLue> fileAttente(UUID structureId) {
        return lister("""
                WHERE structure_id = ? AND statut = 'en_attente'
                  AND (created_at AT TIME ZONE 'UTC')::date = (now() AT TIME ZONE 'UTC')::date
                ORDER BY created_at ASC LIMIT 100
                """, structureId);
    }

    /** Encaissement espèces — forward-only (paye est terminal). */
    @Transactional
    public void encaisser(UUID id, long montantXof, UUID caissier, Instant date) {
        jdbc.update("""
                UPDATE payments.frais_acces
                   SET statut = 'paye', montant_xof = ?, encaisse_par = ?, encaisse_le = ?
                 WHERE id = ?
                """,
                montantXof, caissier, Timestamp.from(date), id);
    }

    /** Exonération — forward-only (exonere est terminal), nature + motif déjà validés. */
    @Transactional
    public void exonerer(UUID id, String nature, String motif, UUID decideur, Instant date) {
        jdbc.update("""
                UPDATE payments.frais_acces
                   SET statut = 'exonere', exoneration_nature = ?, exoneration_motif = ?,
                       exoneration_decidee_par = ?
                 WHERE id = ?
                """,
                nature, motif, decideur, id);
    }

    private List<FraisAccesLue> lister(String filtre, Object... parametres) {
        return jdbc.query("SELECT " + COLONNES + " FROM payments.frais_acces " + filtre,
                (rs, i) -> new FraisAccesLue(
                        rs.getObject("id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("structure_id", UUID.class),
                        rs.getString("statut"),
                        rs.getLong("montant_xof"),
                        rs.getString("exoneration_nature"),
                        rs.getString("exoneration_motif"),
                        rs.getObject("exoneration_decidee_par", UUID.class),
                        rs.getObject("encaisse_par", UUID.class),
                        rs.getTimestamp("encaisse_le") == null
                                ? null : rs.getTimestamp("encaisse_le").toInstant(),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                parametres);
    }

    /** Statut du ticket du jour pour la porte consultation (I5) — null si absent. */
    @Transactional(readOnly = true)
    public String statutDuJour(UUID patientId, UUID structureId) {
        return duJour(patientId, structureId).map(FraisAccesLue::statut).orElse(null);
    }

    /** Jour UTC courant (cible des tests — lisibilité). */
    public static LocalDate jourUtc() {
        return Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
}
