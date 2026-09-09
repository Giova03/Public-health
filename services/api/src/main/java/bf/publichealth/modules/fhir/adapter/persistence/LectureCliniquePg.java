package bf.publichealth.modules.fhir.adapter.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.fhir.application.LectureCliniqueFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.ResultatRecherche;

/**
 * Adaptateur de lecture du miroir clinique (schéma clinical, V3) — SELECT
 * mono-schéma uniquement, JdbcTemplate positionnel (requêtes statiques).
 * Le miroir est append-only : on relit l'historique, jamais autre chose.
 */
@Repository
public class LectureCliniquePg implements LectureCliniqueFhir {

    private final JdbcTemplate jdbc;

    public LectureCliniquePg(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<EncounterFhir> encounterParId(UUID id) {
        List<EncounterFhir> rencontres = jdbc.query(
                """
                SELECT id, patient_id, encounter_class, reason, started_at, ended_at
                FROM clinical.encounter WHERE id = ?
                """,
                (rs, i) -> mapperEncounter(rs), id);
        return rencontres.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public ResultatRecherche<EncounterFhir> encountersParPatient(UUID patientId, int limite, int decalage) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.encounter WHERE patient_id = ?",
                Long.class, patientId);
        List<EncounterFhir> rencontres = jdbc.query(
                """
                SELECT id, patient_id, encounter_class, reason, started_at, ended_at
                FROM clinical.encounter WHERE patient_id = ?
                ORDER BY started_at DESC, id ASC LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapperEncounter(rs), patientId, limite, decalage);
        return new ResultatRecherche<>(rencontres, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<ObservationFhir> observationParId(UUID id) {
        List<ObservationFhir> observations = jdbc.query(
                """
                SELECT id, encounter_id, patient_id, code, value_text, value_num, status, effective_at
                FROM clinical.observation WHERE id = ?
                """,
                (rs, i) -> mapperObservation(rs), id);
        return observations.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public ResultatRecherche<ObservationFhir> observations(UUID patientId, UUID encounterId,
                                                           int limite, int decalage) {
        StringBuilder where = new StringBuilder(" WHERE ");
        List<Object> parametres = new ArrayList<>();
        if (patientId != null) {
            where.append("patient_id = ?");
            parametres.add(patientId);
        }
        if (encounterId != null) {
            if (patientId != null) {
                where.append(" AND ");
            }
            where.append("encounter_id = ?");
            parametres.add(encounterId);
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.observation" + where, Long.class,
                parametres.toArray());
        List<Object> pageParametres = new ArrayList<>(parametres);
        pageParametres.add(limite);
        pageParametres.add(decalage);
        List<ObservationFhir> observations = jdbc.query(
                """
                SELECT id, encounter_id, patient_id, code, value_text, value_num, status, effective_at
                FROM clinical.observation""" + where
                + " ORDER BY effective_at DESC, id ASC LIMIT ? OFFSET ?",
                (rs, i) -> mapperObservation(rs),
                pageParametres.toArray());
        return new ResultatRecherche<>(observations, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------
    // Condition
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<ConditionFhir> conditionParId(UUID id) {
        List<ConditionFhir> conditions = jdbc.query(
                """
                SELECT id, encounter_id, patient_id, code, clinical_status, recorded_at
                FROM clinical.condition WHERE id = ?
                """,
                (rs, i) -> mapperCondition(rs), id);
        return conditions.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public ResultatRecherche<ConditionFhir> conditionsParPatient(UUID patientId, int limite, int decalage) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.condition WHERE patient_id = ?",
                Long.class, patientId);
        List<ConditionFhir> conditions = jdbc.query(
                """
                SELECT id, encounter_id, patient_id, code, clinical_status, recorded_at
                FROM clinical.condition WHERE patient_id = ?
                ORDER BY recorded_at DESC, id ASC LIMIT ? OFFSET ?
                """,
                (rs, i) -> mapperCondition(rs), patientId, limite, decalage);
        return new ResultatRecherche<>(conditions, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private static EncounterFhir mapperEncounter(ResultSet rs) throws SQLException {
        OffsetDateTime fin = rs.getObject("ended_at", OffsetDateTime.class);
        return new EncounterFhir(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("encounter_class"),
                rs.getString("reason"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                fin == null ? null : fin.toInstant());
    }

    private static ObservationFhir mapperObservation(ResultSet rs) throws SQLException {
        return new ObservationFhir(
                rs.getObject("id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("code"),
                rs.getString("value_text"),
                rs.getBigDecimal("value_num"),
                rs.getString("status"),
                rs.getObject("effective_at", OffsetDateTime.class).toInstant());
    }

    private static ConditionFhir mapperCondition(ResultSet rs) throws SQLException {
        return new ConditionFhir(
                rs.getObject("id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getString("code"),
                rs.getString("clinical_status"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }
}
