package bf.publichealth.modules.administration.domain;

import java.util.UUID;

/**
 * Utilisateur du back-office introuvable — 404 au niveau HTTP (RFC 7807).
 */
public class UtilisateurIntrouvableException extends RuntimeException {

    private final UUID utilisateurId;

    public UtilisateurIntrouvableException(UUID utilisateurId) {
        super("Utilisateur introuvable : " + utilisateurId);
        this.utilisateurId = utilisateurId;
    }

    public UUID getUtilisateurId() {
        return utilisateurId;
    }
}
