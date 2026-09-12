package bf.publichealth.modules.payments.adapter.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ligne de facture — append-only (garde SQL V9 : ni UPDATE ni DELETE).
 * Une erreur de saisie se corrige en annulant la facture et en en
 * émettant une nouvelle, jamais en silence.
 */
@Entity
@Table(name = "invoice_item", schema = "payments")
public class InvoiceItemEntity {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected InvoiceItemEntity() {
    }

    public InvoiceItemEntity(UUID id, UUID invoiceId, String label,
                             BigDecimal quantity, BigDecimal unitPrice) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.label = label;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getLabel() {
        return label;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
