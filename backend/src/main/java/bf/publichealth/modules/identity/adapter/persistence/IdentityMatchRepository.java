package bf.publichealth.modules.identity.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityMatchRepository extends JpaRepository<IdentityMatchEntity, UUID> {

    List<IdentityMatchEntity> findByStatusOrderByCreatedAtDesc(String status);
}
