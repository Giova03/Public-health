package bf.publichealth.modules.payments.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationDiscrepancyRepository
        extends JpaRepository<ReconciliationDiscrepancyEntity, UUID> {

    /** Écarts ouverts : candidats à résolution par le prochain run. */
    List<ReconciliationDiscrepancyEntity> findByResolvedFalseOrderByCreatedAtAsc();

    List<ReconciliationDiscrepancyEntity> findByRunId(UUID runId);

    Optional<ReconciliationDiscrepancyEntity> findByKindAndReferenceAndResolvedFalse(
            String kind, String reference);
}
