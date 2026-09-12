package bf.publichealth.modules.payments.application;

/**
 * Port hexagonal du fournisseur de paiements (FedaPay en production).
 *
 * <p>La réconciliation nocturne interroge le prestataire pour chaque
 * paiement non scellé : c'est LUI qui fait foi (ADR-006), le webhook
 * n'accélère qu'un état déjà certain. Un adaptateur ne lève JAMAIS
 * d'exception métier — un échec d'appel, un timeout, une référence
 * inconnue se traduisent par {@link EtatPrestataire#inconnu()} : le run
 * examine, il ne crash pas.</p>
 *
 * <p>Deux adaptateurs :</p>
 * <ul>
 *   <li>{@code FournisseurSimule} (défaut, tests/dev) : statut lu dans
 *       {@code payments.provider_simulation}, déterministe, sans réseau ;</li>
 *   <li>{@code FournisseurFedaPayHttp} (si {@code fedapay.api-url} est
 *       configuré) : squelette REST réel.</li>
 * </ul>
 */
public interface FournisseurPaiements {

    /** État distant de la transaction référencée par {@code reference} (provider_ref). */
    EtatPrestataire etatDistant(String reference);
}
