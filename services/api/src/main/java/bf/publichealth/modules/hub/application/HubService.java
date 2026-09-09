package bf.publichealth.modules.hub.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.DestinationDejaConnueException;
import bf.publichealth.modules.hub.domain.DestinationInconnueException;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.MessageHubIntrouvableException;
import bf.publichealth.modules.hub.domain.MessageNonMortException;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Ordonnanceur du connecteur HUB — drainage de l'outbox, envoi des
 * messages dus, verdicts, filigranes, relances manuelles, supervision.
 *
 * <p>Le drainage ({@link #drainManuel()}) est LA séquence protocole :
 * <ol>
 *   <li>{@link DraineurOutbox} — outbox → messages queued, MÊME
 *       transaction locale que le marquage {@code published} ;</li>
 *   <li>pour chaque destination active, chaque message dû : départ
 *       (transaction courte), appel transport HORS transaction,
 *       traitement du verdict (transaction courte : acquittement +
 *       filigrane / retransmission avec trempe et gigue / lettre morte
 *       + audit DENIED) ;</li>
 *   <li>rapport : créés, envoyés, acquittés, morts, filigrane par
 *       destination, trous de séquence détectés.</li>
 * </ol>
 * Déclenchement : job planifié (toutes les 30 s par défaut) ou
 * {@code POST /api/v1/hub/drain} (tests, supervision).</p>
 */
@Service
public class HubService {

    private static final Logger LOG = LoggerFactory.getLogger(HubService.class);

    /** Statuts persistés (V13) — garde du filtre de liste. */
    private static final Set<String> STATUTS = Set.of("queued", "sent", "acked", "dead");

    private final EntrepotHub entrepot;
    private final DraineurOutbox draineur;
    private final LivreurMessage livreur;
    private final TransportHub transport;
    private final GestionnaireSecrets secrets;

    /** Taille maximale d'un lot de drainage/envoi (défaut DANS LE CODE). */
    @Value("${hub.drain.lot-max:200}")
    private int lotMax;

    /**
     * Échéance de reprise d'un envoi parti sans conclusion (crash entre
     * départ et verdict) : le message redevient dû, jamais perdu.
     */
    @Value("${hub.envoi.delai-reprise-ms:300000}")
    private long delaiRepriseMs;

    public HubService(EntrepotHub entrepot, DraineurOutbox draineur, LivreurMessage livreur,
                      TransportHub transport, GestionnaireSecrets secrets) {
        this.entrepot = entrepot;
        this.draineur = draineur;
        this.livreur = livreur;
        this.transport = transport;
        this.secrets = secrets;
    }

    /** Rapport du drainage (API de supervision et tests). */
    public record RapportDrain(int messagesCrees, int envoyes, int acquittes, int morts,
                               Map<String, Long> watermarkParDestination,
                               List<LivreurMessage.TrouSequence> trousDetectes) {
    }

    /** Destination enrichie de ses statistiques de livraison. */
    public record DestinationAvecStats(Destination destination,
                                       EntrepotHub.StatsDestination stats) {
    }

    // ------------------------------------------------------------------
    // Drainage + envoi (le cœur)
    // ------------------------------------------------------------------

    /** Drainage complet : outbox → messages → envois → verdicts → rapport. */
    public RapportDrain drainManuel() {
        var creation = draineur.drainer(lotMax);

        int envoyes = 0;
        int acquittes = 0;
        int morts = 0;
        List<LivreurMessage.TrouSequence> trous = new ArrayList<>();

        for (Destination destination : entrepot.destinationsActives()) {
            List<MessageHub> dus = entrepot.messagesDus(destination.id(), lotMax);
            for (MessageHub message : dus) {
                envoyes++;
                Instant maintenant = Instant.now();
                livreur.partir(message, maintenant, maintenant.plusMillis(delaiRepriseMs));

                ReponseTransport reponse;
                try {
                    reponse = transport.envoyer(destination, message);
                } catch (Exception e) {
                    // Le transport en échec technique est un échec TRANSITOIRE :
                    // retransmission avec trempe, la file n'est jamais perdue.
                    LOG.error("ÉCHEC technique du transport HUB vers {} (séquence {}) : {}",
                            destination.code(), message.sequence(), e.getMessage());
                    reponse = ReponseTransport.echecTransitoire(
                            "échec du transport : " + e.getMessage());
                }

                LivreurMessage.ResultatVerdict resultat =
                        livreur.traiterVerdict(message, reponse, maintenant);
                if ("ACQUITTER".equals(resultat.verdict())) {
                    acquittes++;
                } else if ("LETTER_MORTE".equals(resultat.verdict())) {
                    morts++;
                }
                resultat.trou().ifPresent(trous::add);
            }
        }

        Map<String, Long> filigranes = entrepot.filigranesParCode();
        return new RapportDrain(creation.messagesCrees(), envoyes, acquittes, morts,
                filigranes, trous);
    }

    // ------------------------------------------------------------------
    // Relance manuelle d'un message mort (DLQ)
    // ------------------------------------------------------------------

    /** Remet un message MORT en file : queued, tentatives à 0, échéance immédiate. */
    @Transactional
    public EntrepotHub.MessageAvecDestination relancer(UUID messageId) {
        MessageHub message = entrepot.messageParId(messageId)
                .map(EntrepotHub.MessageAvecDestination::message)
                .orElseThrow(() -> new MessageHubIntrouvableException(messageId));
        if (!message.mort()) {
            throw new MessageNonMortException(messageId, message.status());
        }
        entrepot.remettreEnFile(messageId, Instant.now());
        entrepot.journaliser(messageId, 0, "RETRY_MANUEL",
                "relance manuelle depuis la lettre morte");
        return entrepot.messageParId(messageId)
                .orElseThrow(() -> new MessageHubIntrouvableException(messageId));
    }

    // ------------------------------------------------------------------
    // Destinations
    // ------------------------------------------------------------------

    /**
     * Crée une destination. Le secret est OPTIONNEL : s'il est fourni il
     * est enregistré en mémoire RUNTIME (jamais en base, jamais rendu) ;
     * en production, la configuration {@code hub.secret-destinations}
     * prime à chaque redémarrage.
     */
    @Transactional
    public Destination creerDestination(String code, String baseUrl, Boolean actif, String secret) {
        if (entrepot.destinationParCode(code).isPresent()) {
            throw new DestinationDejaConnueException(code);
        }
        Destination destination = entrepot.creerDestination(code, baseUrl, actif == null || actif);
        if (secret != null && !secret.isBlank()) {
            secrets.enregistrer(code, secret);
        }
        return destination;
    }

    /** Destinations avec statistiques de livraison (supervision — JAMAIS de secret). */
    @Transactional(readOnly = true)
    public List<DestinationAvecStats> destinationsAvecStats() {
        List<DestinationAvecStats> vues = new ArrayList<>();
        for (Destination destination : entrepot.toutesDestinations()) {
            vues.add(new DestinationAvecStats(destination, entrepot.statsDestination(destination.id())));
        }
        return vues;
    }

    // ------------------------------------------------------------------
    // Messages (supervision)
    // ------------------------------------------------------------------

    /** Liste filtrée : statut, destination, limite (défaut 50, récents d'abord). */
    @Transactional(readOnly = true)
    public List<EntrepotHub.MessageAvecDestination> messages(String status, String destination,
                                                             int limite) {
        if (status != null && !STATUTS.contains(status)) {
            throw new IllegalArgumentException("Statut HUB inconnu : " + status
                    + " — valeurs admises : queued, sent, acked, dead");
        }
        if (destination != null && entrepot.destinationParCode(destination).isEmpty()) {
            throw new DestinationInconnueException(destination);
        }
        return entrepot.messages(status, destination, limite);
    }

    /** Message par identifiant (API retry notamment). */
    @Transactional(readOnly = true)
    public EntrepotHub.MessageAvecDestination message(UUID messageId) {
        return entrepot.messageParId(messageId)
                .orElseThrow(() -> new MessageHubIntrouvableException(messageId));
    }
}
