package bf.publichealth.modules.payments.adapter.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import bf.publichealth.modules.payments.domain.StatutFacture;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Facture — mutable par transitions légales, {@code voided} TERMINAL
 * (garde SQL V9 : même un UPDATE direct ne peut ni réécrire l'identité,
 * ni sortir d'un état terminal, ni revenir en arrière).
 *
 * <p>patient_id : uuid nu SANS FK inter-schémas (loi n°3). Le total est
 * calculé par le domaine (InvoiceTotals) — jamais accepté du client.</p>
 */
@Entity
@Table(name = "invoice", schema = "payments")
public class InvoiceEntity {

    @Id
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Convert(converter = StatutFactureConverter.class)
    @Column(name = "status", nullable = false, length = 24)
    private StatutFacture statut = StatutFacture.DRAFT;

    @Column(nullable = false, length = 3)
    private String currency = "XOF";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "voided_reason")
    private String voidedReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    /** Clé d'idempotence de création (V9 UNIQUE). */
    @Column(name = "client_request_id", unique = true)
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected InvoiceEntity() {
    }

    public InvoiceEntity(UUID id, UUID patientId, UUID encounterId, String currency,
                         BigDecimal total, UUID clientRequestId, UUID createdBy) {
        this.id = id;
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.statut = StatutFacture.DRAFT;
        this.currency = currency == null ? "XOF" : currency;
        this.total = total;
        this.clientRequestId = clientRequestId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Émission (transition DÉJÀ validée par le domaine : seul DRAFT → ISSUED). */
    public void emettre() {
        this.statut = StatutFacture.ISSUED;
        this.issuedAt = Instant.now();
    }

    /** Annulation terminale (DRAFT|ISSUED → VOIDED, motif obligatoire). */
    public void annuler(String motif) {
        this.statut = StatutFacture.VOIDED;
        this.voidedReason = motif;
        this.voidedAt = Instant.now();
    }

    /**
     * Encaissement par rapprochement (ISSUED → PARTIALLY_PAID|PAID,
     * PARTIALLY_PAID → PAID) — le cumul des paiements SUCCEEDED liés
     * décide, jamais le client.
     */
    public void appliquerEncaissement(StatutFacture cible) {
        this.statut = cible;
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

    public StatutFacture getStatut() {
        return statut;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getVoidedReason() {
        return voidedReason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
