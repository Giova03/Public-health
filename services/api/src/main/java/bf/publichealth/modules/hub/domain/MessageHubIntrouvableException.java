package bf.publichealth.modules.hub.domain;

import java.util.UUID;

/**
 * Message HUB introuvable — 404 RFC 7807.
 */
public class MessageHubIntrouvableException extends RuntimeException {

    private final UUID messageId;

    public MessageHubIntrouvableException(UUID messageId) {
        super("Message HUB introuvable : " + messageId);
        this.messageId = messageId;
    }

    public UUID getMessageId() {
        return messageId;
    }
}
