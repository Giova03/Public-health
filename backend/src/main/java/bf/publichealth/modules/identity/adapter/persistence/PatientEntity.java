package bf.publichealth.modules.identity.adapter.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Patient (MPI) — dossier doré. Les noms, téléphones et identifiants vivent
 * dans leurs tables propres ; l'agrégat se recompose par lecture.
 * master_id non NULL = fusionné (irréversible), le dossier est inactif.
 */
@Entity
@Table(name = "patient", schema = "identity")
public class PatientEntity {

    @Id
    private UUID id;

    /** PH-AAAA-NNNNNN — lisible à voix haute, unique pour toujours (V6). */
    @Column(name = "ph_reference", length = 16, unique = true)
    private String phReference;

    /** Clé d'idempotence de création côté client offline (V6). */
    @Column(name = "client_request_id", unique = true)
    private UUID clientRequestId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 16)
    private String gender;

    private LocalDate birthDate;

    @Column(name = "birth_date_approximative", nullable = false)
    private boolean birthDateApproximative = false;

    @Column(nullable = false)
    private boolean deceased = false;

    @Column(name = "deceased_at")
    private Instant deceasedAt;

    /** Cause du décès (V14 — alimente le rapport de mortalité SNIS). */
    @Column(name = "cause_deces")
    private String causeDeces;

    /** Non NULL : ce dossier est fusionné dans ce maître (irréversible). */
    @Column(name = "master_id")
    private UUID masterId;

    @Column(nullable = false)
    private long version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PatientEntity() {
    }

    public PatientEntity(UUID id, String phReference, UUID clientRequestId,
                         String gender, LocalDate birthDate, boolean birthDateApproximative) {
        this.id = id;
        this.phReference = phReference;
        this.clientRequestId = clientRequestId;
        this.gender = gender;
        this.birthDate = birthDate;
        this.birthDateApproximative = birthDateApproximative;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Fusion DÉJÀ validée par la revue : irréversible, version++ pour le maître. */
    public void fusionnerDans(UUID master) {
        this.masterId = master;
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void incrementerVersion() {
        this.version++;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPhReference() {
        return phReference;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public Boolean getActive() {
        return active;
    }

    public String getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public boolean isBirthDateApproximative() {
        return birthDateApproximative;
    }

    public UUID getMasterId() {
        return masterId;
    }

    public boolean isDeceased() {
        return deceased;
    }

    public Instant getDeceasedAt() {
        return deceasedAt;
    }

    public String getCauseDeces() {
        return causeDeces;
    }

    /** Déclaration de décès (V14, I15) : scelle le dossier — irréversible. */
    public void declarerDeces(Instant dateDeces, String cause) {
        this.deceased = true;
        this.deceasedAt = dateDeces;
        this.causeDeces = cause;
        this.updatedAt = Instant.now();
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
