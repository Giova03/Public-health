package bf.publichealth.modules.prescription.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItemEntity, UUID> {

    /** Lignes d'une prescription, en ordre de saisie (UUID v7 ordonnés). */
    List<PrescriptionItemEntity> findByPrescriptionIdOrderByIdAsc(UUID prescriptionId);

    /** Ligne rattachée à SA prescription — une ligne d'une autre prescription est introuvable. */
    Optional<PrescriptionItemEntity> findByIdAndPrescriptionId(UUID id, UUID prescriptionId);
}
