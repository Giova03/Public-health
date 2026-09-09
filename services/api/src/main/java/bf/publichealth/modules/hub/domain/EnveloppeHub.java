package bf.publichealth.modules.hub.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Enveloppe HUB — construction <b>normalisée déterministe</b> : les mêmes
 * données produisent TOUJOURS les mêmes octets, donc la même signature
 * HMAC-SHA256.
 *
 * <p>Canonicité (le contrat du protocole) :
 * <ul>
 *   <li>huit champs de premier niveau exactement, {@value #CHAMPS}
 *       — clés <b>triées</b> alphabétiquement ;</li>
 *   <li>sérialisation <b>compacte</b> : aucun espace, aucun passage à la
 *       ligne entre les séparateurs ;</li>
 *   <li>le {@code payload} est inséré tel quel (arbre JSON déjà canonique :
 *       la forme texte du jsonb PostgreSQL est déterministe — même contenu,
 *       mêmes octets) ;</li>
 *   <li>les instants ({@code occurred_at}, {@code emis_a}) en ISO-8601 UTC
 *       ({@link Instant#toString()}).</li>
 * </ul>
 * La signature HMAC-SHA256 porte sur les OCTETS exacts du JSON canonique
 * (UTF-8) — recalculables par le destinataire depuis n'importe quelle
 * représentation du même contenu.</p>
 *
 * @param eventId     {@code event_id} — référence l'événement outbox
 * @param sequence    {@code sequence} — numéro monotone PAR destination
 * @param destination {@code destination} — code de la destination
 * @param eventType   {@code event_type} — ex {@code sync.patient.created}
 * @param aggregateId {@code aggregate_id} — agrégat métier concerné
 * @param occurredAt  {@code occurred_at} — survenance métier
 * @param payload     {@code payload} — charge JSON (texte canonique du jsonb)
 * @param emisA       {@code emis_a} — émission (horodatage de préparation)
 */
public record EnveloppeHub(UUID eventId, long sequence, String destination,
                           String eventType, UUID aggregateId, Instant occurredAt,
                           String payload, Instant emisA) {

    /** Les huit champs de l'enveloppe, dans l'ordre canonique (trié). */
    public static final String[] CHAMPS = {
            "aggregate_id", "destination", "emis_a", "event_id",
            "event_type", "occurred_at", "payload", "sequence"
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    public EnveloppeHub {
        Objects.requireNonNull(eventId, "event_id manquant");
        Objects.requireNonNull(destination, "destination manquante");
        Objects.requireNonNull(eventType, "event_type manquant");
        Objects.requireNonNull(aggregateId, "aggregate_id manquant");
        Objects.requireNonNull(occurredAt, "occurred_at manquant");
        Objects.requireNonNull(payload, "payload manquant");
        Objects.requireNonNull(emisA, "emis_a manquant");
        if (sequence <= 0) {
            throw new IllegalArgumentException("La séquence HUB doit être strictement positive : " + sequence);
        }
    }

    /**
     * Construit l'enveloppe d'un événement outbox pour une destination,
     * avec l'instant d'émission (les autres champs viennent de l'événement).
     */
    public static EnveloppeHub de(EvenementOutbox evenement, String codeDestination,
                                  long sequence, Instant emisA) {
        return new EnveloppeHub(evenement.eventId(), sequence, codeDestination,
                evenement.eventType(), evenement.aggregateId(), evenement.occurredAt(),
                evenement.payload(), emisA);
    }

    /**
     * Le JSON canonique : clés de premier niveau triées, compact, payload
     * inséré comme sous-arbre. Déterministe — même contenu, mêmes octets.
     */
    public String jsonCanonique() {
        JsonNode charge;
        try {
            charge = JSON.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Charge de l'événement " + eventId + " illisible (JSON invalide) : " + e.getMessage(), e);
        }
        Map<String, Object> champs = new TreeMap<>();
        champs.put("aggregate_id", aggregateId.toString());
        champs.put("destination", destination);
        champs.put("emis_a", emisA.toString());
        champs.put("event_id", eventId.toString());
        champs.put("event_type", eventType);
        champs.put("occurred_at", occurredAt.toString());
        champs.put("payload", charge);
        champs.put("sequence", sequence);
        try {
            return JSON.writeValueAsString(champs);
        } catch (Exception e) {
            // Impossible par construction (types simples + JsonNode) : garde fatale.
            throw new IllegalStateException("Sérialisation canonique de l'enveloppe impossible", e);
        }
    }

    /** Les octets UTF-8 exacts sur lesquels porte la signature HMAC-SHA256. */
    public byte[] octetsCanoniques() {
        return jsonCanonique().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Re-canonicalise une enveloppe lue sous forme textuelle (le stockage
     * jsonb restitue les clés de premier niveau dans SON ordre — la
     * re-canonicalisation retaille : clés de premier niveau retriées,
     * sérialisation compacte, sous-arbres préservés tels quels).
     *
     * <p>Stabilité de bout en bout — la propriété qui rend la signature
     * vérifiable par le destinataire : pour une enveloppe donnée,
     * {@code octetsCanoniques(jsonCanonique())} rend EXACTEMENT les octets
     * originaux (le contenu jsonb préserve les valeurs et les sous-arbres,
     * seule la présentation bouge). Le test de canonicité le prouve.</p>
     *
     * @param jsonEnveloppe le JSON de l'enveloppe, n'importe quel ordre de clés
     * @return les octets canoniques UTF-8
     */
    public static byte[] octetsCanoniques(String jsonEnveloppe) {
        JsonNode noeud;
        try {
            noeud = JSON.readTree(jsonEnveloppe);
        } catch (Exception e) {
            throw new IllegalStateException("Enveloppe HUB illisible (JSON invalide) : " + e.getMessage(), e);
        }
        if (noeud == null || !noeud.isObject() || noeud.size() != CHAMPS.length) {
            throw new IllegalStateException(
                    "Enveloppe HUB invalide : " + CHAMPS.length + " champs attendus");
        }
        Map<String, Object> champs = new TreeMap<>();
        noeud.fieldNames().forEachRemaining(champ -> champs.put(champ, noeud.get(champ)));
        try {
            return JSON.writeValueAsString(champs).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Re-canonicalisation de l'enveloppe impossible", e);
        }
    }
}
