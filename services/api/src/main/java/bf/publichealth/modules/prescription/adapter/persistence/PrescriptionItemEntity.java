package bf.publichealth.modules.prescription.adapter.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ligne de prescription — un médicament prescrit, avec sa posologie et
 * la quantité de référence pour la dispensation. Fait clinique
 * append-only : jamais réécrite ni supprimée (garde SQL V8).
 */
@Entity
@Table(name = "prescription_item", schema = "prescription")
public class PrescriptionItemEntity {

    @Id
    private UUID id;

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "medication_code", nullable = false)
    private String medicationCode;

    @Column(name = "medication_label", nullable = false)
    private String medicationLabel;

    @Column
    private String dose;

    @Column
    private String form;

    @Column
    private String route;

    @Column
    private String frequency;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "quantity_prescribed", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantityPrescribed;

    protected PrescriptionItemEntity() {
    }

    public PrescriptionItemEntity(UUID id, UUID prescriptionId, String medicationCode,
                                  String medicationLabel, String dose, String form, String route,
                                  String frequency, Integer durationDays,
                                  BigDecimal quantityPrescribed) {
        this.id = id;
        this.prescriptionId = prescriptionId;
        this.medicationCode = medicationCode;
        this.medicationLabel = medicationLabel;
        this.dose = dose;
        this.form = form;
        this.route = route;
        this.frequency = frequency;
        this.durationDays = durationDays;
        this.quantityPrescribed = quantityPrescribed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrescriptionId() {
        return prescriptionId;
    }

    public String getMedicationCode() {
        return medicationCode;
    }

    public String getMedicationLabel() {
        return medicationLabel;
    }

    public String getDose() {
        return dose;
    }

    public String getForm() {
        return form;
    }

    public String getRoute() {
        return route;
    }

    public String getFrequency() {
        return frequency;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public BigDecimal getQuantityPrescribed() {
        return quantityPrescribed;
    }
}
