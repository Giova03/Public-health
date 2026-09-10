package bf.publichealth.modules.identity.adapter.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Nom d'un patient — un dossier peut en porter plusieurs (officiel, usuel). */
@Entity
@Table(name = "patient_name", schema = "identity")
public class PatientNameEntity {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 16)
    private String use = "official";

    @Column(nullable = false)
    private String family;

    @Column(nullable = false)
    private String given;

    protected PatientNameEntity() {
    }

    public PatientNameEntity(UUID id, UUID patientId, String use, String family, String given) {
        this.id = id;
        this.patientId = patientId;
        this.use = use;
        this.family = family;
        this.given = given;
    }

    /** Déménagement lors d'une fusion (identifiants nationaux, téléphones). */
    public void affecter(UUID nouveauPatient) {
        this.patientId = nouveauPatient;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getUse() {
        return use;
    }

    public String getFamily() {
        return family;
    }

    public String getGiven() {
        return given;
    }
}
