package bf.publichealth.modules.hub.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Ligne du transactional outbox {@code sync.outbox} (V4), telle que vue
 * par le module hub : l'événement métier À PUBLIER vers une destination
 * du PH HUB. Projection en lecture mono-schema (patron MiroirPatientsPg) —
 * le hub est le draineur officiel de l'outbox (porte de migration Kafka).
 *
 * @param eventId     identifiant de l'événement (devient {@code event_id} de l'enveloppe)
 * @param eventType   type, ex {@code sync.patient.created}
 * @param aggregateId identifiant de l'agrégat concerné
 * @param payload     charge utile JSON (forme texte canonique du jsonb PostgreSQL)
 * @param occurredAt  instant de survenance côté métier
 */
public record EvenementOutbox(UUID eventId, String eventType, UUID aggregateId,
                              String payload, Instant occurredAt) {
}
