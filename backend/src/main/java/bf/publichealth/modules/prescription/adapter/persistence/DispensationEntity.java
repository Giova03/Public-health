package bf.publichealth.modules.prescription.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Dispensation — un fait accompli append-only : une délivrance
 * partielle de quantité strictement positive, CUMULÉE avec les
 * précédentes jusqu'à épuisement de la quantité prescrite.
 *
 * <p>Ni mise à jour ni suppression (garde SQL V8) : une erreur de saisie
 * se corrige par contre-entrée sur la prescription (entered-in-error),
 * jamais en silence. client_request_id UNIQUE = idempotence offline.</p>
 */
@Entity
@Table(name = "dispensation", schema = "prescription")
public class DispensationEntity {

    @Id
    private UUID id;

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(name = "dispensed_by")
    private UUID dispensedBy;

    @Column(name = "dispensed_at", nullable = false)
    private Instant dispensedAt;

    /** Clé d'idempotence de dispensation (client offline-first, V8 UNIQUE). */
    @Column(name = "client_request_id", unique = true)
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DispensationEntity() {
    }

    public DispensationEntity(UUID id, UUID prescriptionId, UUID itemId,
                              BigDecimal quantity, UUID dispensedBy, UUID clientRequestId) {
        this.id = id;
        this.prescriptionId = prescriptionId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.dispensedBy = dispensedBy;
        this.clientRequestId = clientRequestId;
        this.dispensedAt = Instant.now();
        this.createdAt = this.dispensedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrescriptionId() {
        return prescriptionId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public UUID getDispensedBy() {
        return dispensedBy;
    }

    public Instant getDispensedAt() {
        return dispensedAt;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
