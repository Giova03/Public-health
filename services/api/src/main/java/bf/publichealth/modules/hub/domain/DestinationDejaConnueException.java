package bf.publichealth.modules.hub.domain;

/**
 * Destination HUB déjà déclarée pour ce code — 409 RFC 7807 (le code est
 * la clé du protocole : séquences et filigrane y sont rattachés).
 */
public class DestinationDejaConnueException extends RuntimeException {

    private final String code;

    public DestinationDejaConnueException(String code) {
        super("Destination HUB déjà déclarée : " + code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
