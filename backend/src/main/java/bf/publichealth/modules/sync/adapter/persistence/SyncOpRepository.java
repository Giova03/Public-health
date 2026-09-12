package bf.publichealth.modules.sync.adapter.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncOpRepository extends JpaRepository<SyncOpEntity, UUID> {
}
