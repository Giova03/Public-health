package bf.publichealth.modules.fhir.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de lecture du miroir clinique (schéma clinical, V3) pour la façade
 * FHIR — SELECT mono-schéma uniquement. Append-only côté écriture : la façade
 * ne fait que relire l'historique.
 */
public interface LectureCliniqueFhir {

    Optional<EncounterFhir> encounterParId(UUID id);

    LecturePatientsFhir.ResultatRecherche<EncounterFhir> encountersParPatient(
            UUID patientId, int limite, int decalage);

    Optional<ObservationFhir> observationParId(UUID id);

    LecturePatientsFhir.ResultatRecherche<ObservationFhir> observations(UUID patientId,
            UUID encounterId, int limite, int decalage);

    Optional<ConditionFhir> conditionParId(UUID id);

    LecturePatientsFhir.ResultatRecherche<ConditionFhir> conditionsParPatient(
            UUID patientId, int limite, int decalage);

    /** Rencontre — colonnes de clinical.encounter (V3). */
    record EncounterFhir(UUID id, UUID patientId, String encounterClass, String reason,
                         Instant startedAt, Instant endedAt) {
    }

    /** Observation append-only — status ∈ {final, entered-in-error} (CHECK V3). */
    record ObservationFhir(UUID id, UUID encounterId, UUID patientId, String code,
                           String valueText, BigDecimal valueNum, String status,
                           Instant effectiveAt) {
    }

    /** Problème de santé — clinical_status est un texte libre borné (V3 sans CHECK). */
    record ConditionFhir(UUID id, UUID encounterId, UUID patientId, String code,
                         String clinicalStatus, Instant recordedAt) {
    }
}
