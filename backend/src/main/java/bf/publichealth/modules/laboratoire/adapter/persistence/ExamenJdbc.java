package bf.publichealth.modules.laboratoire.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des examens de laboratoire — JDBC sur laboratoire.examen (V14).
 * Demande → résultat : la boucle TDR → diagnostic (I6).
 */
@Repository
public class ExamenJdbc {

    private final JdbcTemplate jdbc;

    public ExamenJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Examen(UUID id, UUID patientId, UUID consultationId, UUID structureId,
                         String type, String statut, String resultatText,
                         Boolean resultatPositif, Instant demandeLe, Instant resultatLe,
                         UUID demandePar, UUID saisiPar) {
    }

    @Transactional
    public UUID inserer(UUID id, UUID patientId, UUID consultationId, UUID structureId,
                        String type, UUID demandePar) {
        jdbc.update("""
                INSERT INTO laboratoire.examen
                    (id, patient_id, consultation_id, structure_id, type, statut, demande_par)
                VALUES (?, ?, ?, ?, ?, 'demande', ?)
                """, id, patientId, consultationId, structureId, type, demandePar);
        return id;
    }

    @Transactional
    public void enregistrerResultat(UUID id, String resultatText, Boolean resultatPositif,
                                    UUID saisiPar) {
        jdbc.update("""
                UPDATE laboratoire.examen
                   SET statut = 'resultat', resultat_text = ?, resultat_positif = ?,
                       resultat_le = now(), saisi_par = ?
                 WHERE id = ? AND statut = 'demande'
                """, resultatText, resultatPositif, saisiPar, id);
    }

    @Transactional(readOnly = true)
    public Optional<Examen> trouver(UUID id) {
        return jdbc.query("""
                SELECT id, patient_id, consultation_id, structure_id, type, statut,
                       resultat_text, resultat_positif, demande_le, resultat_le,
                       demande_par, saisi_par
                  FROM laboratoire.examen WHERE id = ?
                """, this::mapper, id).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Examen> lister(UUID patientId, String statut) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, patient_id, consultation_id, structure_id, type, statut,
                       resultat_text, resultat_positif, demande_le, resultat_le,
                       demande_par, saisi_par
                  FROM laboratoire.examen WHERE 1=1
                """);
        List<Object> parametres = new java.util.ArrayList<>();
        if (patientId != null) {
            sql.append(" AND patient_id = ?");
            parametres.add(patientId);
        }
        if (statut != null && !statut.isBlank()) {
            sql.append(" AND statut = ?");
            parametres.add(statut);
        }
        sql.append(" ORDER BY demande_le DESC LIMIT 200");
        return jdbc.query(sql.toString(), this::mapper, parametres.toArray());
    }

    /** Paludisme confirmé ? — au moins un TDR/goutte épaisse positif. */
    @Transactional(readOnly = true)
    public long compterPositifs(UUID structureId, Instant depuis) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM laboratoire.examen
                 WHERE structure_id = ? AND statut = 'resultat'
                   AND resultat_positif = true
                   AND type IN ('tdr_paludisme','goutte_epaisse')
                   AND demande_le >= ?
                """, Long.class, structureId, Timestamp.from(depuis));
        return total == null ? 0 : total;
    }

    private Examen mapper(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        Timestamp resultatLe = rs.getTimestamp("resultat_le");
        return new Examen(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("consultation_id", UUID.class),
                rs.getObject("structure_id", UUID.class),
                rs.getString("type"),
                rs.getString("statut"),
                rs.getString("resultat_text"),
                rs.getObject("resultat_positif") == null ? null : rs.getBoolean("resultat_positif"),
                rs.getTimestamp("demande_le").toInstant(),
                resultatLe == null ? null : resultatLe.toInstant(),
                rs.getObject("demande_par", UUID.class),
                rs.getObject("saisi_par", UUID.class));
    }
}
