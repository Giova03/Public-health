package bf.publichealth.modules.prescription.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import bf.publichealth.modules.prescription.domain.StatutPrescription;

/**
 * Prescription — append-only par conception (migration V8, garde SQL).
 *
 * <p>patient_id est une colonne uuid SANS FK inter-schémas (loi n°3) :
 * l'existence du patient est vérifiée par l'application au moment de la
 * création, via le port {@code PatientLookup} (module identity). La
 * réécriture est interdite par trigger : seules contre-entrées légales,
 * {@code annuler} et {@code passerEnErreur}, toutes deux au départ
 * d'ACTIVE uniquement — le domaine a déjà validé la transition.</p>
 */
@Entity
@Table(name = "prescription", schema = "prescription")
public class PrescriptionEntity {

    /** UUID v7 — généré côté client en mode offline, côté serveur sinon. */
    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "prescriber_id")
    private UUID prescriberId;

    @Column(name = "facility_id")
    private UUID facilityId;

    @Convert(converter = StatutPrescriptionConverter.class)
    @Column(name = "status", nullable = false, length = 24)
    private StatutPrescription statut = StatutPrescription.ACTIVE;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "created_via", nullable = false, length = 8)
    private String createdVia = "online";

    /** Clé d'idempotence de création (client offline-first, V8 UNIQUE). */
    @Column(name = "client_request_id", unique = true)
    private UUID clientRequestId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PrescriptionEntity() {
    }

    public PrescriptionEntity(UUID id, UUID patientId, UUID encounterId,
                              UUID prescriberId, UUID facilityId, Instant issuedAt,
                              String createdVia, UUID clientRequestId) {
        this.id = id;
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.prescriberId = prescriberId;
        this.facilityId = facilityId;
        this.issuedAt = issuedAt;
        this.createdVia = createdVia == null ? "online" : createdVia;
        this.clientRequestId = clientRequestId;
        this.statut = StatutPrescription.ACTIVE;
        this.createdAt = Instant.now();
    }

    /**
     * Annulation logistique (transition DÉJÀ validée par le domaine :
     * seul ACTIVE peut évoluer). Contre-entrée tracée, jamais de
     * réécriture des valeurs cliniques.
     */
    public void annuler(String motif) {
        this.statut = StatutPrescription.CANCELLED;
        this.cancelReason = motif;
        this.cancelledAt = Instant.now();
    }

    /**
     * Contre-entrée d'erreur clinique de saisie — l'équivalent
     * médical de l'annulation, même garde, jamais de DELETE.
     */
    public void passerEnErreur(String motif) {
        this.statut = StatutPrescription.ENTERED_IN_ERROR;
        this.cancelReason = motif;
        this.cancelledAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public UUID getPrescriberId() {
        return prescriberId;
    }

    public UUID getFacilityId() {
        return facilityId;
    }

    public StatutPrescription getStatut() {
        return statut;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getCreatedVia() {
        return createdVia;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
