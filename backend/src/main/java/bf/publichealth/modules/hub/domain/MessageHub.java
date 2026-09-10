package bf.publichealth.modules.hub.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Message HUB persisté (projection de {@code hub.message}) : une enveloppe
 * signée destinée à UNE destination, avec son état de livraison.
 *
 * <p>Statuts (V13) : {@code queued} (en file, prêt), {@code sent}
 * (envoi en cours), {@code acked} (acquitté — le filigrane a avancé),
 * {@code dead} (lettre morte : rejet définitif ou tentatives épuisées ;
 * relançable manuellement par {@code POST /api/v1/hub/messages/{id}/retry}).</p>
 *
 * @param id             identifiant du message
 * @param eventId        référence l'événement sync.outbox (UNIQUE)
 * @param destinationId  destination visée
 * @param sequence       séquence monotone PAR destination
 * @param envelope       enveloppe canonique (jsonb — le contenu signé)
 * @param signature      HMAC-SHA256 hexadécimal des octets canoniques
 * @param status         queued | sent | acked | dead
 * @param attempts       nombre d'échecs déjà subis
 * @param lastError      dernier diagnostic d'échec
 * @param nextAttemptAt  prochaine échéance de retransmission
 * @param sentAt         dernier envoi
 * @param ackedAt        acquittement
 * @param createdAt      création
 */
public record MessageHub(UUID id, UUID eventId, UUID destinationId, long sequence,
                         String envelope, String signature, String status, int attempts,
                         String lastError, Instant nextAttemptAt, Instant sentAt,
                         Instant ackedAt, Instant createdAt) {

    /** Le message attend-il un envoi (queued) ? */
    public boolean enFile() {
        return "queued".equals(status);
    }

    /** Le message est-il mort (DLQ) ? */
    public boolean mort() {
        return "dead".equals(status);
    }
}
