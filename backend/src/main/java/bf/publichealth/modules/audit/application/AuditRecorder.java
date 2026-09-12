package bf.publichealth.modules.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryRepository;

/**
 * Enregistreur d'audit — six dimensions, append-only, chaînage par hachage.
 *
 * <p>Chaque entrée porte SHA-256(canonical(maillon précédent + entrée)) :
 * toute altération rétroactive du journal casse toute la chaîne suivante.
 * REQUIRES_NEW : l'audit survit même à un rollback métier — on doit tracer
 * l'échec, pas seulement le succès.</p>
 */
@Service
public class AuditRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditEntryRepository repository;
    private final ObjectMapper objectMapper;

    public AuditRecorder(AuditEntryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, String entity, UUID entityId,
                       UUID facilityId, String reason, AuditEntryEntity.Result result,
                       Map<String, Object> details) {
        try {
            Instant occurredAt = Instant.now();
            String previousHash = repository.findTopByOrderByIdDesc()
                    .map(AuditEntryEntity::getHash)
                    .orElse(null);
            String detailsJson = details == null ? null : objectMapper.writeValueAsString(details);
            String canonical = "%s|%s|%s|%s|%s|%s|%s|%s|%s".formatted(
                    nullToDash(previousHash), actorId, action, entity, entityId,
                    facilityId, nullToDash(reason), result, occurredAt);
            String hash = sha256Hex(canonical);

            repository.save(new AuditEntryEntity(occurredAt, actorId, action, entity,
                    entityId, facilityId, reason, result, detailsJson, previousHash, hash));
        } catch (Exception e) {
            // L'audit ne doit JAMAIS casser le flux métier, mais l'échec est crié.
            LOG.error("ÉCHEC D'AUDIT action={} entité={} résultat={} : {}", action, entity, result, e.getMessage());
        }
    }

    private static String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
