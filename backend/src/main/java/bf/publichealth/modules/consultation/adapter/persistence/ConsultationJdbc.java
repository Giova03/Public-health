package bf.publichealth.modules.consultation.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistance des consultations — JDBC pur sur le schéma clinical.*
 * (V3, append-only). L'UUID v7 est généré côté serveur pour la voie
 * en ligne (le client offline passe par l'uplink E2 qui porte ses
 * propres UUID, même cible).
 *
 * <p>Une consultation = un encounter (class 'consultation', reason =
 * motif) + ses observations (constantes TA/température/poids + notes
 * + libellé diagnostic) + une condition (diagnostic codé). TOUT est
 * persisté : l'audit I4 reprochait au registre papier du CSPS de
 * contenir plus que la plateforme.</p>
 */
@Repository
public class ConsultationJdbc {

    private final JdbcTemplate jdbc;

    public ConsultationJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Une constante vitale à enregistrer (code stable + valeur). */
    public record Constante(String code, Double valeur) {
    }

    @Transactional
    public UUID inserer(UUID id, UUID patientId, UUID facilityId, UUID practitionerId,
                        String motif, String diagnosticCode, String diagnosticLabel,
                        String notes, List<Constante> constantes, Instant date) {
        jdbc.update("""
                INSERT INTO clinical.encounter
                    (id, patient_id, facility_id, practitioner_id, encounter_class,
                     reason, started_at, created_via)
                VALUES (?, ?, ?, ?, 'consultation', ?, ?, 'online')
                """,
                id, patientId, facilityId, practitionerId, motif, Timestamp.from(date));

        // Constantes vitales — observations numériques (codes stables).
        for (Constante constante : constantes) {
            insererObservation(id, patientId, constante.code(), null, constante.valeur(), date);
        }
        // Notes cliniques + libellé lisible du diagnostic — observations textuelles.
        if (notes != null && !notes.isBlank()) {
            insererObservation(id, patientId, "NOTES_CLINIQUES", notes.trim(), null, date);
        }
        if (diagnosticLabel != null && !diagnosticLabel.isBlank()) {
            insererObservation(id, patientId, "DIAGNOSTIC_LIBELLE", diagnosticLabel.trim(), null, date);
        }
        // Diagnostic — condition CODÉE (le référentiel est imposé par l'appelant,
        // la plateforme n'accepte plus d'opinion non codée — I14).
        jdbc.update("""
                INSERT INTO clinical.condition
                    (id, encounter_id, patient_id, code, clinical_status, recorded_at)
                VALUES (?, ?, ?, ?, 'active', now())
                """,
                UUID.randomUUID(), id, patientId, diagnosticCode);
        return id;
    }

    private void insererObservation(UUID encounterId, UUID patientId, String code,
                                    String texte, Double valeur, Instant date) {
        jdbc.update("""
                INSERT INTO clinical.observation
                    (id, encounter_id, patient_id, code, value_text, value_num,
                     status, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, 'final', ?)
                """,
                UUID.randomUUID(), encounterId, patientId, code, texte, valeur,
                Timestamp.from(date));
    }

    /** Vue assemblée d'une consultation (motif + diagnostic + constantes). */
    public record ConsultationLue(UUID id, UUID patientId, UUID facilityId, UUID practitionerId,
                                  String motif, String diagnosticCode, String diagnosticLabel,
                                  String notes, Map<String, Double> constantes,
                                  Instant date, List<UUID> examens) {
    }

    private record ObservationLue(String code, String texte, Double valeur) {
    }

    private record EncounterBrut(UUID id, UUID patientId, UUID facilityId, UUID practitionerId,
                                 String motif, Instant date) {
    }

    @Transactional(readOnly = true)
    public List<ConsultationLue> listerParPatient(UUID patientId) {
        return encounters("WHERE patient_id = ? AND encounter_class = 'consultation' "
                + "ORDER BY started_at DESC", patientId).stream()
                .map(e -> assembler(e))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ConsultationLue> trouver(UUID id) {
        List<EncounterBrut> encounters = encounters(
                "WHERE id = ? AND encounter_class = 'consultation'", id);
        if (encounters.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assembler(encounters.get(0)));
    }

    private List<EncounterBrut> encounters(String filtre, Object... parametres) {
        return jdbc.query("""
                SELECT id, patient_id, facility_id, practitioner_id, reason, started_at
                  FROM clinical.encounter
                """ + filtre,
                (rs, i) -> new EncounterBrut(
                        rs.getObject("id", UUID.class),
                        rs.getObject("patient_id", UUID.class),
                        rs.getObject("facility_id", UUID.class),
                        rs.getObject("practitioner_id", UUID.class),
                        rs.getString("reason"),
                        rs.getTimestamp("started_at").toInstant()),
                parametres);
    }

    private ConsultationLue assembler(EncounterBrut encounter) {
        Map<String, Double> constantes = new LinkedHashMap<>();
        String notes = null;
        String diagnosticLabel = null;
        for (ObservationLue observation : observationsDe(encounter.id())) {
            switch (observation.code()) {
                case "NOTES_CLINIQUES" -> notes = observation.texte();
                case "DIAGNOSTIC_LIBELLE" -> diagnosticLabel = observation.texte();
                default -> {
                    if (observation.valeur() != null) {
                        constantes.put(observation.code(), observation.valeur());
                    }
                }
            }
        }
        List<String> diagnostics = jdbc.queryForList("""
                SELECT code FROM clinical.condition WHERE encounter_id = ?
                ORDER BY recorded_at ASC LIMIT 1
                """, String.class, encounter.id());
        String diagnosticCode = diagnostics.isEmpty() ? null : diagnostics.get(0);

        List<UUID> examens = jdbc.queryForList(
                "SELECT id FROM laboratoire.examen WHERE consultation_id = ? ORDER BY demande_le",
                UUID.class, encounter.id());

        return new ConsultationLue(encounter.id(), encounter.patientId(), encounter.facilityId(),
                encounter.practitionerId(), encounter.motif(), diagnosticCode, diagnosticLabel,
                notes, constantes, encounter.date(), examens);
    }

    private List<ObservationLue> observationsDe(UUID encounterId) {
        return jdbc.query("""
                SELECT code, value_text, value_num FROM clinical.observation
                 WHERE encounter_id = ? AND status = 'final'
                """,
                (rs, i) -> new ObservationLue(rs.getString("code"), rs.getString("value_text"),
                        rs.getObject("value_num") == null
                                ? null : rs.getDouble("value_num")),
                encounterId);
    }

    /** Le patient existe-t-il ET n'est-il pas décédé ? (sert la I15.) */
    @Transactional(readOnly = true)
    public String etatPatient(UUID patientId) {
        List<String> etats = jdbc.queryForList(
                "SELECT CASE WHEN deceased THEN 'decede' ELSE 'actif' END FROM identity.patient WHERE id = ?",
                String.class, patientId);
        return etats.isEmpty() ? "introuvable" : etats.get(0);
    }
}
