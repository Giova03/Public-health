package bf.publichealth.modules.identity.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientIdentifierRepository extends JpaRepository<PatientIdentifierEntity, UUID> {

    List<PatientIdentifierEntity> findByPatientId(UUID patientId);

    List<PatientIdentifierEntity> findByValue(String value);
}
