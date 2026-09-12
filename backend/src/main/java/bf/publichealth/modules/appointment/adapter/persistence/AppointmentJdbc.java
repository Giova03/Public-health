package bf.publichealth.modules.appointment.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des rendez-vous — JDBC sur rendezvous.rendez_vous (V14).
 * Les transitions d'état passent par majEtat() qui VERIFIE la légalité
 * temporelle (I10 : un RDV passé est immuable, sauf honoré/absent
 * consigné par l'agent).
 */
@Repository
public class AppointmentJdbc {

    private final JdbcTemplate jdbc;

    public AppointmentJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RendezVous(UUID id, UUID patientId, UUID structureId, UUID practitionerId,
                             String type, Instant creneau, String statut, String motif,
                             String demandePar, String motifAnnulation, Instant createdAt,
                             UUID createdBy) {
    }

    @Transactional
    public UUID inserer(UUID id, UUID patientId, UUID structureId, UUID practitionerId,
                        String type, Instant creneau, String motif, String demandePar,
                        UUID clientRequestId, UUID createdBy) {
        jdbc.update("""
                INSERT INTO rendezvous.rendez_vous
                    (id, patient_id, structure_id, practitioner_id, type, creneau,
                     statut, motif, demande_par, client_request_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'demande', ?, ?, ?, ?)
                """,
                id, patientId, structureId, practitionerId, type, Timestamp.from(creneau),
                motif, demandePar, clientRequestId, createdBy);
        return id;
    }

    @Transactional(readOnly = true)
    public Optional<RendezVous> trouver(UUID id) {
        return jdbc.query("""
                SELECT id, patient_id, structure_id, practitioner_id, type, creneau, statut,
                       motif, demande_par, motif_annulation, created_at, created_by
                  FROM rendezvous.rendez_vous WHERE id = ?
                """, this::mapper, id).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<RendezVous> lister(UUID patientId, UUID structureId, String statut) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, patient_id, structure_id, practitioner_id, type, creneau, statut,
                       motif, demande_par, motif_annulation, created_at, created_by
                  FROM rendezvous.rendez_vous WHERE 1=1
                """);
        java.util.List<Object> parametres = new java.util.ArrayList<>();
        if (patientId != null) {
            sql.append(" AND patient_id = ?");
            parametres.add(patientId);
        }
        if (structureId != null) {
            sql.append(" AND structure_id = ?");
            parametres.add(structureId);
        }
        if (statut != null && !statut.isBlank()) {
            sql.append(" AND statut = ?");
            parametres.add(statut);
        }
        sql.append(" ORDER BY creneau DESC LIMIT 200");
        return jdbc.query(sql.toString(), this::mapper, parametres.toArray());
    }

    @Transactional(readOnly = true)
    public Optional<UUID> parClientRequestId(UUID clientRequestId) {
        return jdbc.queryForList(
                "SELECT id FROM rendezvous.rendez_vous WHERE client_request_id = ?",
                UUID.class, clientRequestId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public long compterNonAnnulesDuJour(UUID patientId, Instant creneau) {
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM rendezvous.rendez_vous
                 WHERE patient_id = ?
                   AND statut NOT IN ('annule','absent')
                   AND creneau >= date_trunc('day', ?::timestamptz)
                   AND creneau < date_trunc('day', ?::timestamptz) + interval '1 day'
                """, Long.class, patientId, Timestamp.from(creneau), Timestamp.from(creneau));
        return total == null ? 0 : total;
    }

    /** Transition d'état — le service a DÉJÀ validé la légalité. */
    @Transactional
    public void majEtat(UUID id, String statut, String motifAnnulation, UUID acteur) {
        jdbc.update("""
                UPDATE rendezvous.rendez_vous
                   SET statut = ?, motif_annulation = ?, annule_par = ?, updated_at = now()
                 WHERE id = ?
                """, statut, motifAnnulation, acteur, id);
    }

    private RendezVous mapper(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new RendezVous(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("structure_id", UUID.class),
                rs.getObject("practitioner_id", UUID.class),
                rs.getString("type"),
                rs.getTimestamp("creneau").toInstant(),
                rs.getString("statut"),
                rs.getString("motif"),
                rs.getString("demande_par"),
                rs.getString("motif_annulation"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class));
    }
}
