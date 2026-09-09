package bf.publichealth.modules.payments.adapter.fournisseur;

import java.math.BigDecimal;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bf.publichealth.modules.payments.application.EtatPrestataire;
import bf.publichealth.modules.payments.application.FournisseurPaiements;
import bf.publichealth.modules.payments.domain.StatutPrestataire;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fournisseur FedaPay HTTP — squelette REST réel (épique E4).
 *
 * <p>Activé UNIQUEMENT si {@code fedapay.api-url} est configuré
 * (sinon le fournisseur simulé sert les tests/dev). AUCUN credential
 * réel : la clé d'API, si elle existe, vient de la propriété
 * {@code fedapay.api-key} (environnement, jamais du code). Un échec
 * d'appel — timeout, 4xx/5xx, illisible — se traduit par
 * {@link EtatPrestataire#inconnu()} : le run EXAMINE, il ne crash pas.</p>
 *
 * <p><b>NON TESTÉ en intégration</b> (aucune dépendance réseau en CI) :
 * le contrat d'API FedaPay ({@code GET {api-url}/transactions/{reference}}
 * → {@code {status, amount, id}}) est une HYPOTHÈSE À VALIDER avec la
 * documentation officielle — même posture que les en-têtes de webhook
 * (V2). Statuts reconnus : succeeded, pending, failed, cancelled
 * (insensible à la casse) ; toute autre valeur = INCONNU.</p>
 */
public class FournisseurFedaPayHttp implements FournisseurPaiements {

    private static final Logger LOG = LoggerFactory.getLogger(FournisseurFedaPayHttp.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public FournisseurFedaPayHttp(String apiUrl, String apiKey, Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build());
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .requestInterceptor((requete, corps, execution) -> {
                    if (apiKey != null && !apiKey.isBlank()) {
                        requete.getHeaders().setBearerAuth(apiKey);
                    }
                    return execution.execute(requete, corps);
                })
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public EtatPrestataire etatDistant(String reference) {
        if (reference == null || reference.isBlank()) {
            return EtatPrestataire.inconnu();
        }
        try {
            String corps = restClient.get()
                    .uri("/transactions/{reference}", reference)
                    .retrieve()
                    .body(String.class);
            JsonNode noeud = objectMapper.readTree(corps == null ? "" : corps);
            String statut = noeud.path("status").asText(null);
            StatutPrestataire resolu = statut == null ? null : switch (statut.toLowerCase()) {
                case "succeeded" -> StatutPrestataire.SUCCEEDED;
                case "pending" -> StatutPrestataire.PENDING;
                case "failed" -> StatutPrestataire.FAILED;
                case "cancelled" -> StatutPrestataire.CANCELLED;
                default -> null;
            };
            if (resolu == null) {
                return EtatPrestataire.inconnu();
            }
            BigDecimal montant = noeud.path("amount").isNumber()
                    ? noeud.path("amount").decimalValue() : null;
            String id = noeud.path("id").isTextual() ? noeud.path("id").asText() : null;
            return new EtatPrestataire(resolu, montant, id);
        } catch (Exception e) {
            // Prestataire muet : on n'en déduit rien, on examine.
            LOG.warn("FedaPay muet pour la référence {} : {}", reference, e.getMessage());
            return EtatPrestataire.inconnu();
        }
    }
}
