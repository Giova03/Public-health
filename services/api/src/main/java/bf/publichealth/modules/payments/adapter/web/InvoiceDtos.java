package bf.publichealth.modules.payments.adapter.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import bf.publichealth.modules.payments.adapter.persistence.InvoiceEntity;
import bf.publichealth.modules.payments.adapter.persistence.InvoiceItemEntity;
import bf.publichealth.modules.payments.application.InvoiceService;
import bf.publichealth.modules.payments.domain.InvoiceTotals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** DTO du module payments (facturation) — immuables, contrats stables (loi n°5). */
public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    // ------------------------------------------------------------------
    // Requêtes
    // ------------------------------------------------------------------

    public record CreateInvoiceRequest(
            @NotNull UUID patientId,
            UUID encounterId,
            @Size(min = 3, max = 3) String currency,
            UUID clientRequestId,
            UUID createdBy,
            @NotEmpty(message = "Une facture porte au moins une ligne")
            @Valid
            List<ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank @Size(max = 256) String label,
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal quantity,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) {

        public ItemRequest {
            // Normalisation présentation : la colonne est numeric(12,2).
            if (quantity != null) {
                quantity = quantity.setScale(2, RoundingMode.HALF_UP);
            }
            if (unitPrice != null) {
                unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    public record IssueRequest(
            UUID issuedBy) {
    }

    public record VoidRequest(
            @NotBlank(message = "Le motif d'annulation de la facture est OBLIGATOIRE")
            String reason,
            UUID voidedBy) {
    }

    // ------------------------------------------------------------------
    // Réponses
    // ------------------------------------------------------------------

    /** Vue complète : facture + lignes + cumul encaissé (rapprochement). */
    public record InvoiceResponse(
            UUID id, UUID patientId, UUID encounterId, String status, String currency,
            BigDecimal total, Instant issuedAt, String voidedReason, Instant voidedAt,
            UUID clientRequestId, Instant createdAt, UUID createdBy,
            BigDecimal cumulEncaisse, List<ItemResponse> items) {

        public static InvoiceResponse from(InvoiceService.InvoiceDetail vue) {
            InvoiceEntity f = vue.facture();
            return new InvoiceResponse(f.getId(), f.getPatientId(), f.getEncounterId(),
                    f.getStatut().getCode(), f.getCurrency(), f.getTotal(), f.getIssuedAt(),
                    f.getVoidedReason(), f.getVoidedAt(), f.getClientRequestId(), f.getCreatedAt(),
                    f.getCreatedBy(), vue.cumulEncaisse(),
                    vue.lignes().stream().map(ItemResponse::from).toList());
        }
    }

    /** Résumé pour les listes : sans lignes ni cumul. */
    public record InvoiceResume(
            UUID id, UUID patientId, UUID encounterId, String status, String currency,
            BigDecimal total, Instant issuedAt, String voidedReason, Instant voidedAt,
            UUID clientRequestId, Instant createdAt, UUID createdBy) {

        public static InvoiceResume from(InvoiceEntity f) {
            return new InvoiceResume(f.getId(), f.getPatientId(), f.getEncounterId(),
                    f.getStatut().getCode(), f.getCurrency(), f.getTotal(), f.getIssuedAt(),
                    f.getVoidedReason(), f.getVoidedAt(), f.getClientRequestId(), f.getCreatedAt(),
                    f.getCreatedBy());
        }
    }

    public record ItemResponse(
            UUID id, String label, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {

        public static ItemResponse from(InvoiceItemEntity e) {
            return new ItemResponse(e.getId(), e.getLabel(), e.getQuantity(), e.getUnitPrice(),
                    InvoiceTotals.montantLigne(new InvoiceTotals.LigneFacture(e.getLabel(),
                            e.getQuantity(), e.getUnitPrice())));
        }
    }
}
