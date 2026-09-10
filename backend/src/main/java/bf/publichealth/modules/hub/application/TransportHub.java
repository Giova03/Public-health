package bf.publichealth.modules.hub.application;

import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Port du transport HUB — la frontière « étrangère » du module (loi
 * architecturale n°4). Deux adaptateurs :
 * <ul>
 *   <li>{@code TransportSimule} (défaut) : scénarios déterministes semés
 *       dans {@code hub.simulation_reponse}, pour le dev et les tests ;</li>
 *   <li>{@code TransportHttps} : POST réel vers {@code base_url} (HTTPS
 *       443, vérification TLS standard), activé par
 *       {@code hub.transport=https}.</li>
 * </ul>
 *
 * <p>Le transport ne lève JAMAIS d'exception pour un échec de livraison :
 * il répond par {@link ReponseTransport} (acquittement / échec
 * transitoire / rejet définitif). Une exception technique est rattrapée
 * par l'ordonnanceur et traitée comme échec transitoire.</p>
 */
public interface TransportHub {

    /**
     * Envoie un message signé vers la destination.
     *
     * @param destination la destination (code, base_url)
     * @param message     le message (enveloppe canonique relecture
     *                    comprise, signature HMAC-SHA256, séquence)
     * @return la réponse du partenaire, jamais null
     */
    ReponseTransport envoyer(Destination destination, MessageHub message);
}
