package bf.publichealth.modules.identity.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Identifiant d'un patient. NUNP/CNIB/ANCIEN_REGISTRE : uniques en base
 * (index partiel V1). LOCAL : les registres propres à chaque structure
 * sanitaire peuvent coexister.
 */
@Entity
@Table(name = "patient_identifier", schema = "identity")
public class PatientIdentifierEntity {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 32)
    private String system;

    @Column(nullable = false)
    private String value;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    protected PatientIdentifierEntity() {
    }

    public PatientIdentifierEntity(UUID id, UUID patientId, String system, String value) {
        this.id = id;
        this.patientId = patientId;
        this.system = system;
        this.value = value;
    }

    /** Déménagement lors d'une fusion : l'identifiant suit le maître. */
    public void affecter(UUID nouveauPatient) {
        this.patientId = nouveauPatient;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getSystem() {
        return system;
    }

    public String getValue() {
        return value;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
