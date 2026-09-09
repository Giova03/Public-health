package bf.publichealth.modules.identity.adapter.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Téléphone ou courriel d'un patient — pivot fort du rapprochement. */
@Entity
@Table(name = "patient_telecom", schema = "identity")
public class PatientTelecomEntity {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 16)
    private String system;

    @Column(nullable = false)
    private String value;

    @Column(nullable = false, length = 16)
    private String use = "mobile";

    protected PatientTelecomEntity() {
    }

    public PatientTelecomEntity(UUID id, UUID patientId, String system, String value, String use) {
        this.id = id;
        this.patientId = patientId;
        this.system = system;
        this.value = value;
        this.use = use == null ? "mobile" : use;
    }

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

    public String getUse() {
        return use;
    }
}
