package bf.publichealth.modules.reference.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance référence/contre-référence — JDBC sur reference.fiche (V14).
 * La fiche numérique remplace les 3 volets papier : origine, motif,
 * destination, statut, contre-référence obligatoire pour clore.
 */
@Repository
public class ReferenceJdbc {

    private final JdbcTemplate jdbc;

    public ReferenceJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Fiche(UUID id, UUID patientId, UUID structureOrigine,
                       UUID structureDestination, String motif, boolean urgence,
                       String statut, UUID createdBy, Instant createdAt,
                       Instant recueLe, String contreReference,
                       Instant contreReferenceLe) {
    }

    @Transactional
    public UUID inserer(UUID id, UUID patientId, UUID structureOrigine,
                        UUID structureDestination, String motif, boolean urgence,
                        UUID clientRequestId, UUID createdBy) {
        jdbc.update("""
                INSERT INTO reference.fiche
                    (id, patient_id, structure_origine, structure_destination, motif,
                     urgence, statut, created_by, client_request_id)
                VALUES (?, ?, ?, ?, ?, ?, 'envoyee', ?, ?)
                """, id, patientId, structureOrigine, structureDestination, motif,
                urgence, createdBy, clientRequestId);
        return id;
    }

    @Transactional
    public boolean majStatut(UUID id, String statut) {
        int lignes = jdbc.update("""
                UPDATE reference.fiche
                   SET statut = ?, recue_le = CASE WHEN ? = 'recue' THEN now() ELSE recue_le END
                 WHERE id = ?
                """, statut, statut, id);
        return lignes > 0;
    }

    @Transactional
    public boolean enregistrerContreReference(UUID id, String resume, UUID acteur) {
        int lignes = jdbc.update("""
                UPDATE reference.fiche
                   SET statut = 'retournee', contre_reference = ?,
                       contre_reference_le = now(), contre_reference_par = ?
                 WHERE id = ? AND statut IN ('recue','hospitalisee')
                """, resume, acteur, id);
        return lignes > 0;
    }

    @Transactional(readOnly = true)
    public Optional<Fiche> trouver(UUID id) {
        return jdbc.query("""
                SELECT id, patient_id, structure_origine, structure_destination, motif, urgence,
                       statut, created_by, created_at, recue_le, contre_reference,
                       contre_reference_le
                  FROM reference.fiche WHERE id = ?
                """, this::mapper, id).stream().findFirst();
    }

    /** Filtres : patient, structure (origine OU destination), statut. */
    @Transactional(readOnly = true)
    public List<Fiche> lister(UUID patientId, UUID structureId, String statut) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, patient_id, structure_origine, structure_destination, motif, urgence,
                       statut, created_by, created_at, recue_le, contre_reference,
                       contre_reference_le
                  FROM reference.fiche WHERE 1=1
                """);
        List<Object> parametres = new ArrayList<>();
        if (patientId != null) {
            sql.append(" AND patient_id = ?");
            parametres.add(patientId);
        }
        if (structureId != null) {
            sql.append(" AND (structure_origine = ? OR structure_destination = ?)");
            parametres.add(structureId);
            parametres.add(structureId);
        }
        if (statut != null && !statut.isBlank()) {
            sql.append(" AND statut = ?");
            parametres.add(statut);
        }
        sql.append(" ORDER BY created_at DESC LIMIT 200");
        return jdbc.query(sql.toString(), this::mapper, parametres.toArray());
    }

    /** Références non abouties : envoyées depuis plus de 48 h sans réception (I7). */
    @Transactional(readOnly = true)
    public long compterNonAbouties(UUID structureOrigine) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM reference.fiche
                 WHERE structure_origine = ? AND statut = 'envoyee'
                   AND created_at < now() - interval '48 hours'
                """, Long.class, structureOrigine);
        return total == null ? 0 : total;
    }

    private Fiche mapper(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp recueLe = rs.getTimestamp("recue_le");
        Timestamp contreLe = rs.getTimestamp("contre_reference_le");
        return new Fiche(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("structure_origine", UUID.class),
                rs.getObject("structure_destination", UUID.class),
                rs.getString("motif"),
                rs.getBoolean("urgence"),
                rs.getString("statut"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                recueLe == null ? null : recueLe.toInstant(),
                rs.getString("contre_reference"),
                contreLe == null ? null : contreLe.toInstant());
    }
}
