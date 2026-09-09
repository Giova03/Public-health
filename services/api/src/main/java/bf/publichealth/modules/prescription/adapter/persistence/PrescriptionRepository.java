package bf.publichealth.modules.prescription.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionRepository extends JpaRepository<PrescriptionEntity, UUID> {

    /** Idempotence offline : une clé client = une prescription, jamais deux. */
    Optional<PrescriptionEntity> findByClientRequestId(UUID clientRequestId);

    /** Dossier pharmacologique du patient, la plus récente d'abord. */
    List<PrescriptionEntity> findByPatientIdOrderByIssuedAtDescIdDesc(UUID patientId);
}
