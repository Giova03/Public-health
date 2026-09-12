package bf.publichealth.modules.prescription.adapter.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import bf.publichealth.modules.prescription.adapter.persistence.DispensationEntity;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionEntity;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionItemEntity;
import bf.publichealth.modules.prescription.application.PrescriptionService;

/** DTO du module prescription — immuables, contrats stables (loi n°5). */
public final class PrescriptionDtos {

    private PrescriptionDtos() {
    }

    // ------------------------------------------------------------------
    // Requêtes
    // ------------------------------------------------------------------

    public record CreatePrescriptionRequest(
            UUID id,
            @NotNull UUID patientId,
            UUID encounterId,
            UUID prescriberId,
            UUID facilityId,
            UUID clientRequestId,
            @Pattern(regexp = "online|offline",
                     message = "createdVia doit valoir 'online' ou 'offline'")
            String createdVia,
            Instant issuedAt,
            @NotEmpty(message = "Une prescription porte au moins une ligne de médicament")
            @Valid
            List<ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank String medicationCode,
            @NotBlank String medicationLabel,
            @Size(max = 256) String dose,
            @Size(max = 64) String form,
            @Size(max = 64) String route,
            @Size(max = 128) String frequency,
            @Positive Integer durationDays,
            @NotNull @Positive @Digits(integer = 12, fraction = 2)
            BigDecimal quantityPrescribed) {

        public ItemRequest {
            // Normalisation présentation : la colonne est numeric(12,2) —
            // on parle TOUJOURS en centièmes (15 devient 15.00).
            if (quantityPrescribed != null) {
                quantityPrescribed = quantityPrescribed.setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    public record DispenseRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal quantity,
            UUID clientRequestId,
            UUID dispensedBy) {

        public DispenseRequest {
            // Normalisation présentation : centièmes, comme numeric(12,2).
            if (quantity != null) {
                quantity = quantity.setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    public record CancelRequest(
            @NotBlank(message = "Le motif de la contre-entrée est OBLIGATOIRE")
            String reason) {
    }

    // ------------------------------------------------------------------
    // Réponses
    // ------------------------------------------------------------------

    /** Vue complète : prescription + lignes avec cumul et restant + dispensations. */
    public record PrescriptionResponse(
            UUID id, UUID patientId, UUID encounterId, UUID prescriberId, UUID facilityId,
            String status, Instant issuedAt, String createdVia, UUID clientRequestId,
            String cancelReason, Instant cancelledAt, Instant createdAt,
            List<ItemResponse> items, List<DispensationResponse> dispensations) {

        public static PrescriptionResponse detail(PrescriptionService.PrescriptionDetail vue) {
            PrescriptionEntity p = vue.prescription();
            return new PrescriptionResponse(p.getId(), p.getPatientId(), p.getEncounterId(),
                    p.getPrescriberId(), p.getFacilityId(), p.getStatut().getCode(),
                    p.getIssuedAt(), p.getCreatedVia(), p.getClientRequestId(),
                    p.getCancelReason(), p.getCancelledAt(), p.getCreatedAt(),
                    vue.lignes().stream().map(ItemResponse::from).toList(),
                    vue.dispensations().stream().map(DispensationResponse::from).toList());
        }

        /** Résumé pour les listes : sans lignes ni dispensations. */
        public static PrescriptionResponse resume(PrescriptionEntity p) {
            return new PrescriptionResponse(p.getId(), p.getPatientId(), p.getEncounterId(),
                    p.getPrescriberId(), p.getFacilityId(), p.getStatut().getCode(),
                    p.getIssuedAt(), p.getCreatedVia(), p.getClientRequestId(),
                    p.getCancelReason(), p.getCancelledAt(), p.getCreatedAt(),
                    List.of(), List.of());
        }
    }

    /** Ligne enrichie : cumul dispensé et restant (le comptoir lit ici). */
    public record ItemResponse(
            UUID id, String medicationCode, String medicationLabel, String dose, String form,
            String route, String frequency, Integer durationDays,
            BigDecimal quantityPrescribed, BigDecimal quantityDispensed, BigDecimal restant) {

        public static ItemResponse from(PrescriptionService.LigneDetail ligne) {
            PrescriptionItemEntity e = ligne.ligne();
            return new ItemResponse(e.getId(), e.getMedicationCode(), e.getMedicationLabel(),
                    e.getDose(), e.getForm(), e.getRoute(), e.getFrequency(),
                    e.getDurationDays(), e.getQuantityPrescribed(),
                    ligne.quantiteDispensee(), ligne.restant());
        }
    }

    public record DispensationResponse(
            UUID id, UUID prescriptionId, UUID itemId, BigDecimal quantity,
            UUID dispensedBy, Instant dispensedAt, UUID clientRequestId, Instant createdAt) {

        public static DispensationResponse from(DispensationEntity e) {
            return new DispensationResponse(e.getId(), e.getPrescriptionId(), e.getItemId(),
                    e.getQuantity(), e.getDispensedBy(), e.getDispensedAt(),
                    e.getClientRequestId(), e.getCreatedAt());
        }
    }

    /** Résultat d'une dispensation : le fait + le restant après cumul. */
    public record DispenseResponse(
            UUID id, UUID prescriptionId, UUID itemId, BigDecimal quantity,
            UUID dispensedBy, Instant dispensedAt, UUID clientRequestId, Instant createdAt,
            BigDecimal restant) {

        public static DispenseResponse from(PrescriptionService.DispensationResultat resultat) {
            DispensationEntity e = resultat.dispensation();
            return new DispenseResponse(e.getId(), e.getPrescriptionId(), e.getItemId(),
                    e.getQuantity(), e.getDispensedBy(), e.getDispensedAt(),
                    e.getClientRequestId(), e.getCreatedAt(), resultat.restant());
        }
    }
}
