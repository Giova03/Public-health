package bf.publichealth.modules.hub.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.EvenementOutbox;
import bf.publichealth.modules.hub.domain.MessageHub;

/**
 * Port de persistance du module hub — le schéma {@code hub} ET la
 * fenêtre mono-schema sur {@code sync.outbox} (le hub est le draineur
 * officiel de l'outbox : lecture des événements non publiés et
 * marquage {@code published}, jamais de JOIN inter-schemas — patron
 * accepté MiroirPatientsPg).
 */
public interface EntrepotHub {

    /** Statistiques de livraison d'une destination (comptages par statut). */
    record StatsDestination(long enFile, long enCours, long acquittes, long morts) {

        public long total() {
            return enFile + enCours + acquittes + morts;
        }
    }

    /** Message enrichi du code de sa destination (JOIN intra-module hub). */
    record MessageAvecDestination(MessageHub message, String destinationCode) {
    }

    // ------------------------------------------------------------------
    // Destinations
    // ------------------------------------------------------------------

    /** Destinations actives, triées par id (ordre de verrouillage déterministe). */
    List<Destination> destinationsActives();

    /** Toutes les destinations (supervision), triées par code. */
    List<Destination> toutesDestinations();

    /** Destination par code. */
    Optional<Destination> destinationParCode(String code);

    /** Destination par identifiant. */
    Optional<Destination> destinationParId(UUID id);

    /**
     * Verrou applicatif : relit la destination ligne verrouillée
     * ({@code FOR UPDATE}) — sérialise l'attribution des séquences et
     * les avancées de filigrane par destination.
     */
    Optional<Destination> verrouillerDestination(UUID id);

    /** Crée une destination (watermark 0). Le code doit être inédit. */
    Destination creerDestination(String code, String baseUrl, boolean actif);

    // ------------------------------------------------------------------
    // Outbox (fenêtre mono-schema sur sync.outbox — V4)
    // ------------------------------------------------------------------

    /** Événements non publiés, ordre de survenance (occurred_at, event_id). */
    List<EvenementOutbox> evenementsNonPublies(int limite);

    /**
     * Marque les événements publiés ({@code published = true}) — DOIT
     * participer à la MÊME transaction locale que la création des
     * messages hub (transactional outbox : jamais de divergence).
     */
    int marquerPublies(List<UUID> eventIds);

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    /** Séquence maximale déjà attribuée pour la destination (0 si aucune). */
    long sequenceMax(UUID destinationId);

    /** Enregistre un message queued (l'adaptateur persiste envelope jsonb). */
    void enregistrerMessage(MessageHub message);

    /**
     * Messages dus pour la destination : statut queued/sent et échéance
     * de retransmission atteinte (ou jamais posée — reprise d'envoi).
     */
    List<MessageHub> messagesDus(UUID destinationId, int limite);

    /** Message par identifiant (avec le code de sa destination). */
    Optional<MessageAvecDestination> messageParId(UUID id);

    /** Liste filtrée, récents d'abord (created_at DESC), avec destination. */
    List<MessageAvecDestination> messages(String status, String destinationCode, int limite);

    /** Passe le message à {@code sent} avec une échéance de reprise. */
    void marquerEnvoye(UUID messageId, Instant envoyeA, Instant repriseA);

    /** Acquitte le message ({@code acked}). */
    void marquerAcquitte(UUID messageId, Instant acquitteA);

    /** Échec transitoire : retour en file, tentative comptée, prochaine échéance. */
    void marquerEchec(UUID messageId, int nouveauxAttempts, Instant prochainEssai, String erreur);

    /** Lettre morte ({@code dead}) — la tentative fautive est comptée. */
    void marquerMort(UUID messageId, int tentativesEffectuees, Instant a, String raison);

    /** Relance manuelle d'un message mort : queued, tentatives remises à 0. */
    void remettreEnFile(UUID messageId, Instant maintenant);

    /** Journal append-only des tentatives ({@code hub.delivery_log}). */
    void journaliser(UUID messageId, int attempt, String outcome, String detail);

    // ------------------------------------------------------------------
    // Filigrane & supervision
    // ------------------------------------------------------------------

    /**
     * Fait avancer le filigrane — strictement monotone :
     * {@code GREATEST(watermark, sequence)}, jamais en arrière.
     */
    void avancerWatermark(UUID destinationId, long sequence);

    /** Filigranes actuels, code destination → filigrane. */
    Map<String, Long> filigranesParCode();

    /** Statistiques de livraison d'une destination. */
    StatsDestination statsDestination(UUID destinationId);
}
