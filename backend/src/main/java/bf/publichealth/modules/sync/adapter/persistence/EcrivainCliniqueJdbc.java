package bf.publichealth.modules.sync.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.sync.application.EcrivainClinique;

/**
 * Insertions cliniques offline — append-only, UUID côté client,
 * created_via='offline', synced_at laissé NULL par protocole.
 *
 * <p>Les gardes SQL de V3 (trigger anti-UPDATE/DELETE sur observation)
 * restent la ligne de défense finale, quelle que soit la porte.</p>
 */
@Repository
public class EcrivainCliniqueJdbc implements EcrivainClinique {

    /** Liste blanche : le nom d'entité ne devient JAMAIS du SQL libre. */
    private static final Map<String, String> TABLES = Map.of(
            "encounter", "clinical.encounter",
            "observation", "clinical.observation",
            "condition", "clinical.condition");

    private final JdbcTemplate jdbc;

    public EcrivainCliniqueJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existe(String entite, UUID id) {
        String table = TABLES.get(entite);
        if (table == null) {
            throw new IllegalArgumentException("Entité clinique inconnue : " + entite);
        }
        Integer occurrences = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return occurrences != null && occurrences > 0;
    }

    @Override
    public void insererEncounter(UUID id, Encounter charge) {
        // synced_at omis : NULL par protocole (le miroir descendant le posera).
        jdbc.update("""
                INSERT INTO clinical.encounter
                    (id, patient_id, facility_id, practitioner_id,
                     encounter_class, reason, started_at, ended_at, created_via)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'offline')
                """,
                id, charge.patientId(), charge.facilityId(), charge.practitionerId(),
                charge.encounterClass(), charge.reason(),
                odt(charge.startedAt()),
                charge.endedAt() == null ? null : odt(charge.endedAt()));
    }

    @Override
    public void insererObservation(UUID id, Observation charge) {
        jdbc.update("""
                INSERT INTO clinical.observation
                    (id, encounter_id, patient_id, code, value_text, value_num, status, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, charge.encounterId(), charge.patientId(), charge.code(),
                charge.valueText(), charge.valueNum(),
                charge.status() == null ? "final" : charge.status(),
                odt(charge.effectiveAt()));
    }

    @Override
    public void insererCondition(UUID id, Condition charge) {
        jdbc.update("""
                INSERT INTO clinical.condition
                    (id, encounter_id, patient_id, code, clinical_status, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, charge.encounterId(), charge.patientId(), charge.code(),
                charge.clinicalStatus() == null ? "active" : charge.clinicalStatus(),
                charge.recordedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC)
                        : odt(charge.recordedAt()));
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
