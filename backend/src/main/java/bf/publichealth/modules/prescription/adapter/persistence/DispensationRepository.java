package bf.publichealth.modules.prescription.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DispensationRepository extends JpaRepository<DispensationEntity, UUID> {

    /** Idempotence offline : une clé client = une dispensation, jamais deux. */
    Optional<DispensationEntity> findByClientRequestId(UUID clientRequestId);

    /** Toutes les dispensations d'une prescription, en ordre de saisie (cumul). */
    List<DispensationEntity> findByPrescriptionIdOrderByIdAsc(UUID prescriptionId);

    /** Dispensations cumulées d'une ligne (le cumul fait foi). */
    List<DispensationEntity> findByItemIdOrderByIdAsc(UUID itemId);
}
