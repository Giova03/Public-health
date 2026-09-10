package bf.publichealth.modules.sync.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncDeviceRepository extends JpaRepository<SyncDeviceEntity, UUID> {

    Optional<SyncDeviceEntity> findByUserIdAndDeviceName(UUID userId, String deviceName);

    /** Déclaration sans compte (user_id NULL) — unicité via l'index V7. */
    Optional<SyncDeviceEntity> findByUserIdIsNullAndDeviceName(String deviceName);
}
