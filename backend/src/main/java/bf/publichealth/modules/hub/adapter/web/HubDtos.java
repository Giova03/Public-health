package bf.publichealth.modules.hub.adapter.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import bf.publichealth.modules.hub.application.EntrepotHub;
import bf.publichealth.modules.hub.application.HubService;
import bf.publichealth.modules.hub.application.LivreurMessage;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.MessageHub;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO du module hub — immuables, contrats stables. Nommage du protocole
 * (snake_case) rendu TEL QUEL. Le secret HMAC n'apparaît JAMAIS : ni en
 * lecture, ni en réponse de création.
 */
public final class HubDtos {

    private HubDtos() {
    }

    /** Rapport du drainage (POST /api/v1/hub/drain — supervision et tests). */
    public record RapportDrainResponse(int messages_crees, int envoyes, int acquittes,
                                        int morts,
                                        Map<String, Long> watermark_par_destination,
                                        List<TrouSequenceResponse> trous_detectes) {

        public static RapportDrainResponse from(HubService.RapportDrain rapport) {
            return new RapportDrainResponse(rapport.messagesCrees(), rapport.envoyes(),
                    rapport.acquittes(), rapport.morts(), rapport.watermarkParDestination(),
                    rapport.trousDetectes().stream().map(TrouSequenceResponse::from).toList());
        }
    }

    /** Un trou de séquence détecté pendant le drainage. */
    public record TrouSequenceResponse(String destination, long sequence, long filigrane,
                                        long ecart) {

        public static TrouSequenceResponse from(LivreurMessage.TrouSequence trou) {
            return new TrouSequenceResponse(trou.destination(), trou.sequence(),
                    trou.filigraneAvant(), trou.ecart());
        }
    }

    /** Liste des messages (GET /api/v1/hub/messages — récents d'abord). */
    public record ReponseMessages(List<MessageResponse> messages) {
    }

    /** Un message HUB : enveloppe CANONIQUE complète + signature + état. */
    public record MessageResponse(UUID id, UUID event_id, String destination, long sequence,
                                  JsonNode envelope, String signature, String status,
                                  int attempts, String last_error, Instant next_attempt_at,
                                  Instant sent_at, Instant acked_at, Instant created_at) {

        public static MessageResponse from(EntrepotHub.MessageAvecDestination vue) {
            MessageHub message = vue.message();
            return new MessageResponse(message.id(), message.eventId(), vue.destinationCode(),
                    message.sequence(), Arborescence.lire(message.envelope()), message.signature(),
                    message.status(), message.attempts(), message.lastError(),
                    message.nextAttemptAt(), message.sentAt(), message.ackedAt(),
                    message.createdAt());
        }
    }

    /** Création d'une destination (le secret est optionnel, JAMAIS rendu). */
    public record CreerDestinationRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "code : minuscules, chiffres et tirets")
            String code,
            @NotBlank @Size(max = 512) String base_url,
            Boolean actif,
            @Size(max = 256) String secret) {
    }

    /** Destination rendue par l'API — AUCUN secret, jamais. */
    public record DestinationResponse(UUID id, String code, String base_url, boolean actif,
                                      long watermark) {

        public static DestinationResponse from(Destination destination) {
            return new DestinationResponse(destination.id(), destination.code(),
                    destination.baseUrl(), destination.actif(), destination.watermark());
        }
    }

    /** Destination + statistiques de livraison (GET /api/v1/hub/destinations). */
    public record DestinationAvecStatsResponse(UUID id, String code, String base_url,
                                               boolean actif, long watermark,
                                               StatsResponse stats) {

        public static DestinationAvecStatsResponse from(HubService.DestinationAvecStats vue) {
            EntrepotHub.StatsDestination stats = vue.stats();
            return new DestinationAvecStatsResponse(vue.destination().id(),
                    vue.destination().code(), vue.destination().baseUrl(),
                    vue.destination().actif(), vue.destination().watermark(),
                    new StatsResponse(stats.enFile(), stats.enCours(), stats.acquittes(),
                            stats.morts(), stats.total()));
        }
    }

    /** Comptages par statut d'une destination. */
    public record StatsResponse(long queued, long sent, long acked, long dead, long total) {
    }

    /** Petit lecteur JSON tolérant : l'enveloppe est rendue comme objet. */
    static final class Arborescence {

        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private Arborescence() {
        }

        static JsonNode lire(String json) {
            try {
                return JSON.readTree(json);
            } catch (Exception e) {
                throw new IllegalStateException("Enveloppe HUB illisible : " + e.getMessage(), e);
            }
        }
    }
}
