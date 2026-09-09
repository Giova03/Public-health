package bf.publichealth.modules.hub.domain;

/**
 * Destination HUB inconnue (filtre de liste) — 400 RFC 7807 : un code
 * inconnu dans une requête est une erreur de requête, pas une absence.
 */
public class DestinationInconnueException extends RuntimeException {

    private final String code;

    public DestinationInconnueException(String code) {
        super("Destination HUB inconnue : " + code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
