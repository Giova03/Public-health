package bf.publichealth.modules.identity.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientRepository extends JpaRepository<PatientEntity, UUID> {

    Optional<PatientEntity> findByClientRequestId(UUID clientRequestId);

    Optional<PatientEntity> findByPhReference(String phReference);
}
