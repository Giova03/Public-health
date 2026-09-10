package bf.publichealth.modules.sync.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Appareil déclaré — l'unicité (COALESCE(user_id), device_name) posée
 * en V7 rend la déclaration idempotente : rejouer la même déclaration
 * retourne LE MÊME deviceId.
 */
@Entity
@Table(name = "device", schema = "sync")
public class SyncDeviceEntity {

    @Id
    private UUID id;

    /** NULL depuis V7 : appareil non rattaché à un compte. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "device_name")
    private String deviceName;

    /** Dernier curseur delta consommé par cet appareil (opaque, v1:…). */
    @Column(name = "last_sync_cursor")
    private String lastSyncCursor;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SyncDeviceEntity() {
    }

    public SyncDeviceEntity(UUID id, UUID userId, String deviceName) {
        this.id = id;
        this.userId = userId;
        this.deviceName = deviceName;
        this.lastSeenAt = Instant.now();
        this.createdAt = this.lastSeenAt;
    }

    /** Vu à l'instant, curseur éventuellement rafraîchi. */
    public void marquerVu(String curseur) {
        this.lastSyncCursor = curseur;
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getLastSyncCursor() {
        return lastSyncCursor;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
