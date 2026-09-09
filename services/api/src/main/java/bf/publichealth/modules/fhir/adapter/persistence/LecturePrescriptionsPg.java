package bf.publichealth.modules.fhir.adapter.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.fhir.application.LecturePrescriptionsFhir;

/**
 * Adaptateur de lecture du dossier pharmacologique (schéma prescription, V8).
 *
 * <p>SELECT mono-schéma : le JOIN ligne→prescription de {@link #ligneParId}
 * est INTERNE au schéma (la FK y vit, V8) — légal, contrairement aux JOIN
 * inter-schémas. Le cumul dispensé par ligne arrive en SUM groupée : une
 * seule requête, pas de boucle.</p>
 */
@Repository
public class LecturePrescriptionsPg implements LecturePrescriptionsFhir {

    private final NamedParameterJdbcTemplate jdbc;

    public LecturePrescriptionsPg(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Read (par ligne de prescription → MedicationRequest)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<LigneAvecPrescription> ligneParId(UUID ligneId) {
        List<LigneInterne> lignes = jdbc.query(
                """
                SELECT i.id AS ligne_id, i.medication_code, i.medication_label, i.dose, i.form,
                       i.route, i.frequency, i.duration_days, i.quantity_prescribed,
                       p.id AS prescription_id, p.patient_id, p.encounter_id, p.prescriber_id,
                       p.facility_id, p.status, p.issued_at, p.created_at
                FROM prescription.prescription_item i
                JOIN prescription.prescription p ON p.id = i.prescription_id
                WHERE i.id = :ligneId
                """,
                Map.of("ligneId", ligneId),
                (rs, i) -> mapperLigne(rs));
        if (lignes.isEmpty()) {
            return Optional.empty();
        }
        LigneInterne ligne = lignes.get(0);
        return Optional.of(new LigneAvecPrescription(
                new PrescriptionFhir(ligne.prescriptionId(), ligne.patientId(), ligne.encounterId(),
                        ligne.prescriberId(), ligne.facilityId(), ligne.statut(), ligne.issuedAt(),
                        ligne.createdAt(), List.of()),
                versLigneFhir(ligne, cumulParLigne(List.of(ligne.ligneId()))
                        .getOrDefault(ligne.ligneId(), BigDecimal.ZERO))));
    }

    // ------------------------------------------------------------------
    // Recherche par patient
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionFhir> prescriptionsParPatient(UUID patientId) {
        List<LigneInterne> lignes = jdbc.query(
                """
                SELECT i.id AS ligne_id, i.medication_code, i.medication_label, i.dose, i.form,
                       i.route, i.frequency, i.duration_days, i.quantity_prescribed,
                       p.id AS prescription_id, p.patient_id, p.encounter_id, p.prescriber_id,
                       p.facility_id, p.status, p.issued_at, p.created_at
                FROM prescription.prescription p
                JOIN prescription.prescription_item i ON i.prescription_id = p.id
                WHERE p.patient_id = :patientId
                ORDER BY p.issued_at DESC, p.id ASC, i.id ASC
                """,
                Map.of("patientId", patientId),
                (rs, i) -> mapperLigne(rs));
        if (lignes.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> cumuls = cumulParLigne(
                lignes.stream().map(LigneInterne::ligneId).toList());
        // Ordre du SQL = ordre de construction (LinkedHashMap), les lignes
        // se rangent dans leur prescription au fil de la lecture.
        Map<UUID, List<LigneFhir>> lignesParPrescription = new HashMap<>();
        Map<UUID, LigneInterne> entetes = new java.util.LinkedHashMap<>();
        for (LigneInterne ligne : lignes) {
            entetes.putIfAbsent(ligne.prescriptionId(), ligne);
            lignesParPrescription.computeIfAbsent(ligne.prescriptionId(), k -> new ArrayList<>())
                    .add(versLigneFhir(ligne, cumuls.getOrDefault(ligne.ligneId(), BigDecimal.ZERO)));
        }
        return entetes.values().stream()
                .map(entete -> new PrescriptionFhir(entete.prescriptionId(), entete.patientId(),
                        entete.encounterId(), entete.prescriberId(), entete.facilityId(),
                        entete.statut(), entete.issuedAt(), entete.createdAt(),
                        lignesParPrescription.get(entete.prescriptionId())))
                .toList();
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private record LigneInterne(UUID ligneId, String medicationCode, String medicationLabel,
                               String dose, String form, String route, String frequency,
                               Integer durationDays, BigDecimal quantityPrescribed,
                               UUID prescriptionId, UUID patientId, UUID encounterId,
                               UUID prescriberId, UUID facilityId, String statut,
                               Instant issuedAt, Instant createdAt) {
    }

    private static LigneInterne mapperLigne(ResultSet rs) throws SQLException {
        return new LigneInterne(
                rs.getObject("ligne_id", UUID.class),
                rs.getString("medication_code"),
                rs.getString("medication_label"),
                rs.getString("dose"),
                rs.getString("form"),
                rs.getString("route"),
                rs.getString("frequency"),
                rs.getObject("duration_days", Integer.class),
                rs.getBigDecimal("quantity_prescribed"),
                rs.getObject("prescription_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("encounter_id", UUID.class),
                rs.getObject("prescriber_id", UUID.class),
                rs.getObject("facility_id", UUID.class),
                rs.getString("status"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static LigneFhir versLigneFhir(LigneInterne ligne, BigDecimal cumul) {
        return new LigneFhir(ligne.ligneId(), ligne.medicationCode(), ligne.medicationLabel(),
                ligne.dose(), ligne.form(), ligne.route(), ligne.frequency(), ligne.durationDays(),
                ligne.quantityPrescribed(), cumul);
    }

    /** Cumul dispensé par ligne — SUM groupée, une requête. */
    private Map<UUID, BigDecimal> cumulParLigne(List<UUID> lignesIds) {
        Map<UUID, BigDecimal> cumuls = new HashMap<>();
        if (lignesIds.isEmpty()) {
            return cumuls;
        }
        jdbc.query("""
                        SELECT item_id, coalesce(sum(quantity), 0) AS cumul
                        FROM prescription.dispensation WHERE item_id IN (:ids)
                        GROUP BY item_id""",
                Map.of("ids", lignesIds),
                rs -> {
                    cumuls.put(rs.getObject("item_id", UUID.class), rs.getBigDecimal("cumul"));
                });
        return cumuls;
    }
}
