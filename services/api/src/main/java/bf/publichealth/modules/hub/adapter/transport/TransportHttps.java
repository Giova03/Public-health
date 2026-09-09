package bf.publichealth.modules.hub.adapter.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bf.publichealth.modules.hub.application.TransportHub;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.EnveloppeHub;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Transport HTTPS RÉEL vers un partenaire du PH HUB (production) —
 * SQUELETTE documenté, activé par {@code hub.transport=https}, NON
 * testé en intégration (aucune dépendance réseau en CI).
 *
 * <p>Protocole physique (à verrouiller avec le partenaire) :
 * <ul>
 *   <li>{@code POST {base_url}} — HTTPS 443 uniquement, vérification TLS
 *       STANDARD (chaîne de confiance + nom d'hôte, aucun contournement) ;</li>
 *   <li>corps : les OCTETS CANONIQUES de l'enveloppe (re-canonicalisés
 *       depuis le stockage — {@link EnveloppeHub#octetsCanoniques(String)}),
 *       {@code Content-Type: application/json ; charset=UTF-8} ;</li>
 *   <li>en-têtes : {@code X-PH-Signature} = HMAC-SHA256 hexadécimal
 *       minuscule des octets du corps, {@code X-PH-Sequence} = numéro de
 *       séquence monotone, {@code X-PH-Destination} = code destination ;</li>
 *   <li>réponse attendue (2xx) : JSON {@code {"acquitte":true|false,
 *       "definitif":true|false, "watermark":123, "detail":"…"}} ;</li>
 *   <li>2xx sans verdict lisible → échec transitoire ; 408/429/5xx →
 *       échec transitoire ; 4xx autre → rejet définitif ; exception
 *       réseau (délai expiré, DNS…) → l'ordonnanceur traite en échec
 *       transitoire.</li>
 * </ul></p>
 */
public class TransportHttps implements TransportHub {

    private static final Logger LOG = LoggerFactory.getLogger(TransportHttps.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;

    public TransportHttps(Duration timeout) {
        // Squelette : timeouts symétriques (style FournisseurFedaPayHttp),
        // TLS standard, aucun credential — le secret HMAC n'est JAMAIS
        // envoyé, il signe.
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build());
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public ReponseTransport envoyer(Destination destination, MessageHub message) {
        byte[] corps = EnveloppeHub.octetsCanoniques(message.envelope());
        try {
            String corpsReponse = rest.post()
                    .uri(destination.baseUrl())
                    .headers(entetes -> {
                        entetes.set("Content-Type", "application/json ; charset=UTF-8");
                        entetes.set("X-PH-Signature", message.signature());
                        entetes.set("X-PH-Sequence", Long.toString(message.sequence()));
                        entetes.set("X-PH-Destination", destination.code());
                    })
                    .body(new String(corps, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);

            JsonNode noeud = corpsReponse == null ? null : JSON.readTree(corpsReponse);
            if (noeud == null || !noeud.isObject() || !noeud.has("acquitte")) {
                return ReponseTransport.echecTransitoire(
                        "réponse du partenaire sans verdict lisible (contrat {acquitte, definitif})");
            }
            Long filigrane = noeud.hasNonNull("watermark")
                    ? noeud.get("watermark").asLong() : null;
            return new ReponseTransport(
                    noeud.path("acquitte").asBoolean(false),
                    noeud.path("definitif").asBoolean(false),
                    filigrane,
                    noeud.path("detail").asText(null));
        } catch (RestClientResponseException e) {
            // 4xx (hors 408/429) = rejet définitif : retransmettre ne servira
            // rien. 408/429/5xx = transitoire : trempe et gigue.
            boolean transitoire = e.getStatusCode().value() == 408
                    || e.getStatusCode().value() == 429
                    || e.getStatusCode().is5xxServerError();
            return transitoire
                    ? ReponseTransport.echecTransitoire("HTTP " + e.getStatusCode().value()
                            + " du partenaire : " + e.getResponseBodyAsString())
                    : ReponseTransport.rejetDefinitif("HTTP " + e.getStatusCode().value()
                            + " du partenaire : " + e.getResponseBodyAsString());
        } catch (Exception e) {
            // Réseau, DNS, délai expiré : l'ordonnanceur traite en transitoire,
            // mais le diagnostic part quand même au journal.
            LOG.error("ÉCHEC réseau du transport HUB vers {} (séquence {}) : {}",
                    destination.code(), message.sequence(), e.getMessage());
            throw new IllegalStateException("transport HUB injoignable : " + e.getMessage(), e);
        }
    }
}
