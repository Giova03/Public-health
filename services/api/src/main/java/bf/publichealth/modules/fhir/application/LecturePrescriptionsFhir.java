package bf.publichealth.modules.fhir.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de lecture du dossier pharmacologique (schéma prescription, V8) pour
 * la façade FHIR — SELECT mono-schéma (le JOIN ligne→prescription est interne
 * au schéma, la FK y vit : légal, contrairement aux JOIN inter-schémas).
 *
 * <p>Une prescription E3 porte N lignes de médicament : la façade rend une
 * ressource MedicationRequest PAR LIGNE (id = prescription_item.id), avec le
 * statut calculé de la ligne (voir FhirMappers). Le cumul dispensé par ligne
 * est ramené pré-calculé par l'adaptateur (SUM groupée, une seule requête).</p>
 */
public interface LecturePrescriptionsFhir {

    /** Ligne + prescription porteuse, par identifiant de ligne (read). */
    Optional<LigneAvecPrescription> ligneParId(UUID ligneId);

    /** Toutes les prescriptions d'un patient (statuts confondus), la plus
     *  récente d'abord — la façade filtre/pagine ensuite (volumétrie par
     *  patient faible, append-only). */
    List<PrescriptionFhir> prescriptionsParPatient(UUID patientId);

    record LigneAvecPrescription(PrescriptionFhir prescription, LigneFhir ligne) {
    }

    /** Prescription — colonnes de prescription.prescription (V8). */
    record PrescriptionFhir(UUID id, UUID patientId, UUID encounterId, UUID prescriberId,
                            UUID facilityId, String status, Instant issuedAt, Instant createdAt,
                            List<LigneFhir> lignes) {
    }

    /** Ligne de médicament + cumul dispensé (le restant en découle). */
    record LigneFhir(UUID id, String medicationCode, String medicationLabel, String dose,
                     String form, String route, String frequency, Integer durationDays,
                     BigDecimal quantityPrescribed, BigDecimal quantiteDispensee) {
    }
}
