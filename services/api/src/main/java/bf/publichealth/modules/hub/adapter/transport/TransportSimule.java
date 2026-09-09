package bf.publichealth.modules.hub.adapter.transport;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;

import bf.publichealth.modules.hub.application.TransportHub;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Transport SIMULÉ (défaut) — déterministe, pour le dev et les tests.
 *
 * <p>Le scénario par (destination_code, event_id) est semé dans la table
 * {@code hub.simulation_reponse} (migration V13) par INSERT/UPDATE SQL :
 * {@code ACK} (acquittement + filigrane), {@code ECHEC_TRANSITOIRE}
 * (retransmission), {@code REJET_DEFINITIF} (lettre morte immédiate).
 * Un événement NON semé suit le comportement par défaut configurable
 * ({@code hub.transport.simulation.defaut}, ACK par défaut).</p>
 *
 * <p>Aucune dépendance réseau : le draineur de l'outbox reste testable
 * hors ligne. En production ({@code hub.transport=https}), ce bean est
 * remplacé par {@link TransportHttps}.</p>
 */
public class TransportSimule implements TransportHub {

    private static final Logger LOG = LoggerFactory.getLogger(TransportSimule.class);

    /** Comportements semables dans hub.simulation_reponse. */
    private static final String ACK = "ACK";
    private static final String ECHEC_TRANSITOIRE = "ECHEC_TRANSITOIRE";
    private static final String REJET_DEFINITIF = "REJET_DEFINITIF";

    private final JdbcTemplate jdbc;
    private final String comportementDefaut;

    public TransportSimule(JdbcTemplate jdbc, String comportementDefaut) {
        String defaut = comportementDefaut == null ? "" : comportementDefaut.trim().toUpperCase();
        if (!ACK.equals(defaut) && !ECHEC_TRANSITOIRE.equals(defaut)
                && !REJET_DEFINITIF.equals(defaut)) {
            throw new IllegalStateException("hub.transport.simulation.defaut invalide : "
                    + comportementDefaut + " — valeurs admises : ACK, ECHEC_TRANSITOIRE, "
                    + "REJET_DEFINITIF");
        }
        this.jdbc = jdbc;
        this.comportementDefaut = defaut;
    }

    @Override
    public ReponseTransport envoyer(Destination destination, MessageHub message) {
        String comportement = comportementDefaut;
        try {
            List<String> lignes = jdbc.queryForList(
                    "SELECT comportement FROM hub.simulation_reponse "
                            + "WHERE destination_code = ? AND event_id = ?",
                    String.class, destination.code(), message.eventId());
            if (!lignes.isEmpty()) {
                comportement = lignes.get(0);
            }
        } catch (Exception e) {
            // La table de pilotage est absente ou indisponible : comportement
            // par défaut — le drain ne doit jamais tomber pour la simulation.
            LOG.warn("Table hub.simulation_reponse illisible : {}", e.getMessage());
        }

        return switch (comportement) {
            case ACK -> ReponseTransport.acquitte(message.sequence(),
                    "simulation : acquittement de la séquence " + message.sequence());
            case ECHEC_TRANSITOIRE -> ReponseTransport.echecTransitoire(
                    "simulation : échec transitoire (tentative " + (message.attempts() + 1) + ")");
            case REJET_DEFINITIF -> ReponseTransport.rejetDefinitif(
                    "simulation : rejet définitif du destinataire");
            default -> throw new IllegalStateException(
                    "Comportement de simulation inconnu : " + comportement);
        };
    }
}
