package bf.publichealth.modules.payments.application;

/**
 * Vérification de signature HMAC des webhooks PSP.
 * Abstraction : FedaPay aujourd'hui, tout autre fournisseur demain
 * sans toucher au domaine (ADR-006).
 */
public interface SignatureVerifier {

    /**
     * @param rawBody    charge utile brute exactement reçue
     * @param signature  hex (préfixe « sha256= » toléré, à la Stripe)
     * @return true si et seulement si la signature correspond
     */
    boolean verify(String rawBody, String signature);
}
