package bf.publichealth.modules.audit.domain;

import java.util.UUID;

/**
 * Accès d'urgence introuvable — la revue a posteriori vise un registre
 * inconnu. 404 local (RFC 7807), traité par le contrôleur du module.
 */
public class AccesUrgenceIntrouvableException extends RuntimeException {

    private final UUID idAcces;

    public AccesUrgenceIntrouvableException(UUID idAcces) {
        super("Accès d'urgence inconnu : " + idAcces);
        this.idAcces = idAcces;
    }

    public UUID getIdAcces() {
        return idAcces;
    }
}
