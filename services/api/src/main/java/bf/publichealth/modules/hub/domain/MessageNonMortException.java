package bf.publichealth.modules.hub.domain;

import java.util.UUID;

/**
 * Seul un message MORT (lettre morte) peut être relancé manuellement —
 * 409 RFC 7807 sinon (l'état de livraison d'un message vivant n'est pas
 * réinscriptible à la main).
 */
public class MessageNonMortException extends RuntimeException {

    private final UUID messageId;
    private final String statut;

    public MessageNonMortException(UUID messageId, String statut) {
        super("Seul un message mort peut être relancé : " + messageId
                + " est dans l'état " + statut);
        this.messageId = messageId;
        this.statut = statut;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getStatut() {
        return statut;
    }
}
