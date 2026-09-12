package bf.publichealth.modules.payments.adapter.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import bf.publichealth.modules.payments.adapter.persistence.PaymentEntity;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** DTO du module payments — immuables, contrats stables (loi n°5). */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(
            @NotNull UUID invoiceId,
            // XOF : pas de centimes — 12 chiffres entiers, 0 décimales.
            @NotNull @Positive @Digits(integer = 12, fraction = 0) BigDecimal amount,
            @Size(max = 64) String providerRef,
            UUID initiatedBy) {
    }

    public record PaymentResponse(
            UUID id,
            UUID invoiceId,
            BigDecimal amount,
            String currency,
            String provider,
            String providerRef,
            String state,
            Instant createdAt) {

        public static PaymentResponse from(PaymentEntity entity) {
            return new PaymentResponse(entity.getId(), entity.getInvoiceId(),
                    entity.getAmount(), entity.getCurrency(), entity.getProvider(),
                    entity.getProviderRef(), entity.getState().name(), entity.getCreatedAt());
        }
    }

    public record WebhookAckResponse(String status, String message) {
    }
}
