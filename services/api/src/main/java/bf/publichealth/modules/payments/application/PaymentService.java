package bf.publichealth.modules.payments.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.payments.adapter.persistence.PaymentEntity;
import bf.publichealth.modules.payments.adapter.persistence.PaymentRepository;
import bf.publichealth.modules.payments.adapter.persistence.PaymentTransitionEntity;
import bf.publichealth.modules.payments.adapter.persistence.PaymentTransitionRepository;
import bf.publichealth.modules.payments.adapter.persistence.WebhookEventEntity;
import bf.publichealth.modules.payments.adapter.persistence.WebhookEventRepository;
import bf.publichealth.modules.payments.domain.IllegalPaymentTransitionException;
import bf.publichealth.modules.payments.domain.PaymentState;
import bf.publichealth.modules.payments.domain.WebhookSignatureInvalidException;

/**
 * Cas d'usage paiement — initiation idempotente + webhook durci.
 *
 * <p>Ordre de traitement du webhook, strict et sans exception :
 * signature → déduplication par eventId → corrélation → transition
 * (forward-only) → audit. Le webhook accélère ; la réconciliation
 * nocturne (épique E4) fait foi.</p>
 */
@Service
public class PaymentService {

    public record CreatePaymentCommand(String idempotencyKey, UUID invoiceId,
                                        BigDecimal amount, String providerRef,
                                        UUID initiatedBy) {
    }

    public record PaymentCreation(PaymentEntity payment, boolean replayed) {
    }

    public record WebhookAck(String status, String message) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    private static final Map<String, PaymentState> PROVIDER_STATUS_MAP = Map.of(
            "initiated", PaymentState.INITIATED,
            "pending", PaymentState.PENDING,
            "authorized", PaymentState.AUTHORIZED,
            "succeeded", PaymentState.SUCCEEDED,
            "failed", PaymentState.FAILED,
            "cancelled", PaymentState.CANCELLED,
            "refunded", PaymentState.REFUNDED);

    private final PaymentRepository paymentRepository;
    private final PaymentTransitionRepository transitionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final SignatureVerifier signatureVerifier;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentTransitionRepository transitionRepository,
                          WebhookEventRepository webhookEventRepository,
                          SignatureVerifier signatureVerifier,
                          AuditRecorder auditRecorder,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.transitionRepository = transitionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.signatureVerifier = signatureVerifier;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /** Initiation idempotente : une clé d'idempotence = un paiement, jamais deux. */
    @Transactional
    public PaymentCreation create(CreatePaymentCommand command) {
        var existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            // Rejeu réseau : on rend la réponse idempotente (200, même paiement).
            audit(command.initiatedBy(), "PAYMENT_CREATED", "payment", existing.get().getId(),
                    null, "CLE_IDEMPOTENCE_REJOUee", AuditEntryEntity.Result.SUCCESS,
                    Map.of("replayed", true));
            return new PaymentCreation(existing.get(), true);
        }

        var payment = new PaymentEntity(UuidV7.next(), command.invoiceId(),
                command.amount(), command.providerRef(), command.idempotencyKey(),
                command.initiatedBy());
        paymentRepository.save(payment);

        audit(command.initiatedBy(), "PAYMENT_CREATED", "payment", payment.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("amount", command.amount(), "currency", "XOF", "invoiceId", command.invoiceId()));

        // TODO (épique E4) : écrire PaymentCreated dans sync.outbox (même transaction).
        return new PaymentCreation(payment, false);
    }

    public PaymentEntity find(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElse(null);
    }

    /**
     * Webhook FedaPay — durci. Retourne toujours un acquittement exploitable ;
     * les signatures invalides sont rejetées (401) SANS détail exploitable.
     */
    @Transactional
    public WebhookAck applyWebhook(String eventId, String rawBody, String signature) {
        if (!signatureVerifier.verify(rawBody, signature)) {
            webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                    sha256(rawBody), false, "REJECTED"));
            audit(null, "WEBHOOK_RECEIVED", "webhook_event", null, null, null,
                    AuditEntryEntity.Result.DENIED, Map.of("eventId", eventId, "signature", "invalide"));
            throw new WebhookSignatureInvalidException();
        }

        // Déduplication : le fournisseur réessaie, il ne doit pas être puni de nos doublons.
        if (webhookEventRepository.existsById(eventId)) {
            return new WebhookAck("DUPLICATED", "Événement déjà consommé — aucune action");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                    sha256(rawBody), true, "REJECTED"));
            return new WebhookAck("REJECTED", "Charge illisible");
        }
        String reference = node.path("reference").asText(null);
        String status = node.path("status").asText(null);
        PaymentState target = PROVIDER_STATUS_MAP.get(status == null ? "" : status.toLowerCase());

        if (target == null) {
            webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                    sha256(rawBody), true, "REJECTED"));
            return new WebhookAck("REJECTED", "Statut inconnu : " + status);
        }

        var payment = paymentRepository.findByProviderRef(reference);
        if (payment.isEmpty()) {
            // Webhook orphelin = incident : consigné pour investigation, acquitté pour arrêter les rejeux.
            webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                    sha256(rawBody), true, "ORPHAN"));
            LOG.warn("Webhook orphelin eventId={} reference={}", eventId, reference);
            return new WebhookAck("ORPHAN", "Référence inconnue — consigné pour investigation");
        }

        var entity = payment.get();
        var from = entity.getState();
        if (!from.canTransitionTo(target)) {
            // Rétrogradation ou saut d'état : refus, historique intact, trace DENIED.
            webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                    sha256(rawBody), true, "REJECTED"));
            audit(null, "PAYMENT_TRANSITION_DENIED", "payment", entity.getId(), null,
                    "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", from.name(), "to", target.name(), "eventId", eventId));
            throw new IllegalPaymentTransitionException(from, target);
        }

        entity.applyTransition(target);
        paymentRepository.save(entity);
        transitionRepository.save(new PaymentTransitionEntity(entity.getId(), from, target,
                "webhook", eventId));
        webhookEventRepository.save(new WebhookEventEntity(eventId, "fedapay",
                sha256(rawBody), true, "PROCESSED"));

        audit(entity.getInitiatedBy(), "PAYMENT_TRANSITION", "payment", entity.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", from.name(), "to", target.name(), "eventId", eventId));

        return new WebhookAck("PROCESSED", "Transition appliquée : " + from + " → " + target);
    }

    private void audit(UUID actor, String action, String entity, UUID entityId,
                       UUID facilityId, String reason, AuditEntryEntity.Result result,
                       Map<String, Object> details) {
        auditRecorder.record(actor, action, entity, entityId, facilityId, reason, result, details);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
