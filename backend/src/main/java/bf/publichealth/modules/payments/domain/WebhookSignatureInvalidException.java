package bf.publichealth.modules.payments.domain;

/** Signature webhook invalide — rejet générique, sans détail exploitable. */
public class WebhookSignatureInvalidException extends RuntimeException {

    public WebhookSignatureInvalidException() {
        super("Signature du webhook invalide");
    }
}
