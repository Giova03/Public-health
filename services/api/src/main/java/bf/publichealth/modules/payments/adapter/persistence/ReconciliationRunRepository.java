package bf.publichealth.modules.payments.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, UUID> {

    /** Historique des runs, le plus récent d'abord (limit via Pageable). */
    List<ReconciliationRunEntity> findByOrderByStartedAtDesc(Pageable pageable);
}
