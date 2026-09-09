package bf.publichealth.modules.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import bf.publichealth.common.UuidV7;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration du connecteur PH HUB — bout en bout, seed SQL direct de
 * l'outbox et de la table de pilotage du transport simulé.
 *
 * <p>Scénario ordonné (un récit) : drainage nominal (séquences monotones
 * PAR destination, published marqué dans la même transaction, filigrane
 * avancé), échec transitoire ×2 puis acquittement (trempe et gigue,
 * delivery_log complet), rejet définitif (lettre morte + audit DENIED),
 * trou de séquence sur destination neuve (acquittement partiel) — le
 * filigrane ne recule JAMAIS, relance manuelle d'un message mort,
 * signature HMAC recalculée en Java sur l'enveloppe rendue par l'API,
 * filtres et gardes de l'API. La limite de tentatives (N=2, lettre morte
 * par épuisement) vit dans {@link HubDlqIT} — contextes et propriétés
 * distincts.</p>
 *
 * <p>Le job planifié est désactivé ({@code hub.drain.actif=false}) : les
 * étapes pilotent le drainage par POST. Backoff à 0 : les retransmissions
 * sont immédiates. PostgreSQL : Testcontainers en CI (Docker), embarqué
 * zonky en local.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "hub.drain.actif=false",
        "hub.backoff.base-ms=0"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Connecteur PH HUB (drain, HMAC, séquences, filigrane, DLQ, retry)")
class HubIT {

    /** Le secret de développement documenté DANS LE CODE (GestionnaireSecrets). */
    private static final String SECRET_DEV = "dev-secret-hub-a-changer";

    private static final String DESTINATION_SIM = "ph-hub-simulation";
    private static final String DESTINATION_SECONDAIRE = "ph-hub-test-secondaire";
    private static final String DESTINATION_TROU = "ph-hub-test-trou";

    /** Source de base : conteneur en CI, embarqué en local. */
    private static final PostgreSQLContainer<?> POSTGRES = dockerDisponible()
            ? new PostgreSQLContainer<>("postgres:16-alpine") : null;
    private static EmbeddedPostgres EMBARQUE;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        } else {
            try {
                EMBARQUE = EmbeddedPostgres.builder().start();
            } catch (Exception e) {
                throw new IllegalStateException("PostgreSQL embarqué indisponible", e);
            }
        }
    }

    @DynamicPropertySource
    static void sourceDeDonnees(DynamicPropertyRegistry registre) {
        if (POSTGRES != null) {
            registre.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registre.add("spring.datasource.username", POSTGRES::getUsername);
            registre.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registre.add("spring.datasource.url",
                    () -> EMBARQUE.getJdbcUrl("postgres", "postgres"));
            registre.add("spring.datasource.username", () -> "postgres");
            registre.add("spring.datasource.password", () -> "");
        }
    }

    private static boolean dockerDisponible() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // Aides de seed et de lecture
    // ------------------------------------------------------------------

    /** Sème un événement dans l'outbox (mono-schema, INSERT direct). */
    private UUID semerOutbox(String type, String charge, int secondesPassees) {
        UUID eventId = UuidV7.next();
        jdbc.update("INSERT INTO sync.outbox (event_id, event_type, aggregate_id, payload, "
                        + "occurred_at, published) VALUES (?, ?, ?, ?::jsonb, "
                        + "now() - make_interval(secs => ?), false)",
                eventId, type, UuidV7.next(), charge, secondesPassees);
        return eventId;
    }

    /** Sème le comportement du transport simulé pour (destination, event). */
    private void semerSimulation(String destination, UUID eventId, String comportement) {
        jdbc.update("INSERT INTO hub.simulation_reponse (destination_code, event_id, comportement) "
                + "VALUES (?, ?, ?)", destination, eventId, comportement);
    }

    /** Fait évoluer le comportement semé (le test pilote le scénario). */
    private void changerSimulation(String destination, UUID eventId, String comportement) {
        jdbc.update("UPDATE hub.simulation_reponse SET comportement = ? "
                + "WHERE destination_code = ? AND event_id = ?", comportement, destination, eventId);
    }

    /** Identifiant de la destination par code. */
    private UUID idDestination(String code) {
        return jdbc.queryForObject("SELECT id FROM hub.destination WHERE code = ?", UUID.class, code);
    }

    private String texte(String sql, Object... parametres) {
        return jdbc.queryForObject(sql, String.class, parametres);
    }

    private long entier(String sql, Object... parametres) {
        Long valeur = jdbc.queryForObject(sql, Long.class, parametres);
        return valeur == null ? 0L : valeur;
    }

    private String sequenceDe(String destination, UUID eventId) {
        return texte("SELECT sequence FROM hub.message WHERE destination_id = ? AND event_id = ?",
                idDestination(destination), eventId);
    }

    private String statutDe(String destination, UUID eventId) {
        return texte("SELECT status FROM hub.message WHERE destination_id = ? AND event_id = ?",
                idDestination(destination), eventId);
    }

    private UUID idMessageDe(String destination, UUID eventId) {
        return jdbc.queryForObject(
                "SELECT id FROM hub.message WHERE destination_id = ? AND event_id = ?",
                UUID.class, idDestination(destination), eventId);
    }

    /** Déclenche le drainage manuel et rend le corps du rapport. */
    private String drain() throws Exception {
        MvcResult resultat = mockMvc.perform(post("/api/v1/hub/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages_crees").exists())
                .andExpect(jsonPath("$.envoyes").exists())
                .andExpect(jsonPath("$.acquittes").exists())
                .andExpect(jsonPath("$.morts").exists())
                .andExpect(jsonPath("$.watermark_par_destination").exists())
                .andExpect(jsonPath("$.trous_detectes").exists())
                .andReturn();
        return resultat.getResponse().getContentAsString();
    }

    /**
     * Lecture entière d'un champ du RAPPORT de drainage (JsonPath).
     * Nom distinct de {@link #entier(String, Object...)} (JDBC) : sans lui,
     * l'appel {@code entier(sql, DESTINATION)} liait ICI (surcharge
     * (String,String) plus spécifique) et évaluait « ph-hub-simulation »
     * comme chemin JSON.
     */
    private static int champInt(String corps, String chemin) {
        return ((Number) JsonPath.read(corps, chemin)).intValue();
    }

    // ------------------------------------------------------------------
    // Étape 1 — drainage nominal : séquences par destination, published,
    // filigrane ; deux drains séquentiels → séquences distinctes.
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Drain : outbox → messages par destination, séquences 1,2,3…, published=true, ACK → filigrane")
    void etape1_drainNominal() throws Exception {
        // Une destination secondaire (création par l'API : 201, pas de secret).
        mockMvc.perform(post("/api/v1/hub/destinations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","base_url":"https://partenaire.test/hub","secret":"secret-secondaire"}
                                """.formatted(DESTINATION_SECONDAIRE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(DESTINATION_SECONDAIRE))
                .andExpect(jsonPath("$.watermark").value(0))
                .andExpect(jsonPath("$.secret").doesNotExist());

        // Trois événements dans l'outbox.
        UUID e1 = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-1\"}", 90);
        UUID e2 = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-2\"}", 80);
        UUID e3 = semerOutbox("sync.encounter.created", "{\"acte\":\"consultation\"}", 70);

        String rapport = drain();
        // 3 événements × 2 destinations actives.
        assertThat(champInt(rapport, "$.messages_crees")).isEqualTo(6);
        assertThat(champInt(rapport, "$.envoyes")).isEqualTo(6);
        assertThat(champInt(rapport, "$.acquittes")).isEqualTo(6);
        assertThat(champInt(rapport, "$.morts")).isEqualTo(0);

        // published = true : MÊME transaction que la création des messages.
        assertThat(entier("SELECT COUNT(*) FROM sync.outbox WHERE event_id IN (?, ?, ?) "
                + "AND published", e1, e2, e3)).isEqualTo(3);

        // Séquences monotones PAR destination, ordre de survenance.
        assertThat(sequenceDe(DESTINATION_SIM, e1)).isEqualTo("1");
        assertThat(sequenceDe(DESTINATION_SIM, e2)).isEqualTo("2");
        assertThat(sequenceDe(DESTINATION_SIM, e3)).isEqualTo("3");
        assertThat(sequenceDe(DESTINATION_SECONDAIRE, e1)).isEqualTo("1");
        assertThat(sequenceDe(DESTINATION_SECONDAIRE, e3)).isEqualTo("3");

        // Transport simulé ACK (comportement par défaut) → acked + filigrane.
        assertThat(statutDe(DESTINATION_SIM, e1)).isEqualTo("acked");
        assertThat(statutDe(DESTINATION_SECONDAIRE, e2)).isEqualTo("acked");
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(3);
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SECONDAIRE)).isEqualTo(3);

        // Filigranes rendus dans le rapport.
        Map<String, Integer> filigranes =
                JsonPath.read(rapport, "$.watermark_par_destination");
        assertThat(filigranes).containsEntry(DESTINATION_SIM, 3)
                .containsEntry(DESTINATION_SECONDAIRE, 3);

        // Deuxième drain SANS nouvel événement : rien à faire.
        String deuxieme = drain();
        assertThat(champInt(deuxieme, "$.messages_crees")).isEqualTo(0);
        assertThat(champInt(deuxieme, "$.envoyes")).isEqualTo(0);

        // Deux drains SÉQUENTIELS → séquences DISTINCTES (pas de collision) :
        // un quatrième événement reprend à la séquence 4, par destination.
        UUID e4 = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-4\"}", 60);
        String troisieme = drain();
        assertThat(champInt(troisieme, "$.messages_crees")).isEqualTo(2);
        assertThat(sequenceDe(DESTINATION_SIM, e4)).isEqualTo("4");
        assertThat(sequenceDe(DESTINATION_SECONDAIRE, e4)).isEqualTo("4");

        // L'API rend la file : enveloppe (objet), signature, tentatives.
        mockMvc.perform(get("/api/v1/hub/messages")
                        .param("destination", DESTINATION_SIM)
                        .param("status", "acked")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[0].envelope.event_type")
                        .value("sync.patient.created"))
                .andExpect(jsonPath("$.messages[0].envelope.sequence").isNumber())
                .andExpect(jsonPath("$.messages[0].signature").isString())
                .andExpect(jsonPath("$.messages[0].attempts").value(0));
    }

    // ------------------------------------------------------------------
    // Étape 2 — échec transitoire ×2 puis acquittement.
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("Échec transitoire ×2 puis ACK : attempts=2, delivery_log complet, filigrane avancé")
    void etape2_echecTransitoirePuisAck() throws Exception {
        UUID event = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-TRANSITOIRE\"}", 50);
        semerSimulation(DESTINATION_SIM, event, "ECHEC_TRANSITOIRE");

        // Premier envoi : échec → queued, attempts=1, prochaine échéance (backoff 0).
        String premier = drain();
        assertThat(champInt(premier, "$.acquittes")).isEqualTo(1);  // secondaire
        assertThat(statutDe(DESTINATION_SIM, event)).isEqualTo("queued");
        assertThat(entier("SELECT attempts FROM hub.message WHERE destination_id = ? AND event_id = ?",
                idDestination(DESTINATION_SIM), event)).isEqualTo(1);

        // Deuxième envoi : échec encore → attempts=2 (toujours sous la limite 8).
        drain();
        assertThat(statutDe(DESTINATION_SIM, event)).isEqualTo("queued");
        assertThat(entier("SELECT attempts FROM hub.message WHERE destination_id = ? AND event_id = ?",
                idDestination(DESTINATION_SIM), event)).isEqualTo(2);

        // Le partenaire se rassagit : acquittement.
        changerSimulation(DESTINATION_SIM, event, "ACK");
        String troisieme = drain();
        assertThat(statutDe(DESTINATION_SIM, event)).isEqualTo("acked");
        assertThat(champInt(troisieme, "$.acquittes")).isEqualTo(1);
        // attempts reste à 2 : l'acquittement ne compte pas comme échec.
        assertThat(entier("SELECT attempts FROM hub.message WHERE destination_id = ? AND event_id = ?",
                idDestination(DESTINATION_SIM), event)).isEqualTo(2);

        // Le filigrane avance (l'acquittement de la séquence 5).
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(5);

        // delivery_log : l'historique COMPLET des tentatives, append-only.
        UUID message = idMessageDe(DESTINATION_SIM, event);
        List<Map<String, Object>> journal = jdbc.queryForList(
                "SELECT attempt, outcome FROM hub.delivery_log WHERE message_id = ? "
                        + "ORDER BY attempt", message);
        assertThat(journal).hasSize(3);
        assertThat(journal.get(0)).containsEntry("attempt", 1)
                .containsEntry("outcome", "ECHEC_TRANSITOIRE");
        assertThat(journal.get(1)).containsEntry("attempt", 2)
                .containsEntry("outcome", "ECHEC_TRANSITOIRE");
        assertThat(journal.get(2)).containsEntry("attempt", 3)
                .containsEntry("outcome", "ACK");
    }

    // ------------------------------------------------------------------
    // Étape 3 — rejet définitif : lettre morte immédiate + audit DENIED.
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("Rejet définitif → DEAD immédiat + audit HUB_DLQ (DENIED)")
    void etape3_rejetDefinitif() throws Exception {
        UUID event = semerOutbox("sync.encounter.created", "{\"acte\":\"hospitalisation\"}", 40);
        semerSimulation(DESTINATION_SIM, event, "REJET_DEFINITIF");

        String rapport = drain();
        assertThat(champInt(rapport, "$.morts")).isEqualTo(1);

        assertThat(statutDe(DESTINATION_SIM, event)).isEqualTo("dead");
        UUID message = idMessageDe(DESTINATION_SIM, event);
        // La tentative fautive est comptée, le diagnostic conservé.
        assertThat(entier("SELECT attempts FROM hub.message WHERE id = ?", message)).isEqualTo(1);
        assertThat(texte("SELECT last_error FROM hub.message WHERE id = ?", message))
                .contains("rejet définitif");

        // Le filigrane n'avance PAS : la séquence n'est pas acquittée.
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(5);

        // Audit DENIED-style (patron payments) : l'échec est tracé, REQUIRES_NEW.
        assertThat(entier("SELECT COUNT(*) FROM audit.entry WHERE action = 'HUB_DLQ' "
                        + "AND entity = 'hub_message' AND entity_id = ?", message))
                .isGreaterThanOrEqualTo(1);

        // La lettre morte est visible par l'API.
        mockMvc.perform(get("/api/v1/hub/messages")
                        .param("status", "dead")
                        .param("destination", DESTINATION_SIM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].id").value(message.toString()));
    }

    // ------------------------------------------------------------------
    // Étape 4 — trou de séquence : destination neuve, acquittement partiel.
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("Trou de séquence : acquittement au-delà d'une séquence jamais confirmée — ERROR + écart, filigrane monotone")
    void etape4_trouDeSequence() throws Exception {
        // Destination neuve (INSERT direct : l'API est déjà prouvée).
        jdbc.update("INSERT INTO hub.destination (id, code, base_url, actif) VALUES (?, ?, ?, true)",
                UuidV7.next(), DESTINATION_TROU, "https://partenaire-trou.test/hub");

        UUID event1 = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-TROU-1\"}", 30);
        UUID event2 = semerOutbox("sync.patient.created", "{\"ph_reference\":\"PH-TROU-2\"}", 20);
        // Séquence 1 : échec transitoire ; séquence 2 : acquittement.
        semerSimulation(DESTINATION_TROU, event1, "ECHEC_TRANSITOIRE");
        semerSimulation(DESTINATION_TROU, event2, "ACK");

        String rapport = drain();
        // 2 événements × 3 destinations actives = 6 messages ; 5 acquittés,
        // 1 échec transitoire (séquence 1 de la destination neuve).
        assertThat(champInt(rapport, "$.messages_crees")).isEqualTo(6);
        assertThat(champInt(rapport, "$.acquittes")).isEqualTo(5);

        // DEUX trous, tous deux exigés par le protocole :
        // (1) destination neuve : la séquence 2 est acquittée alors que le
        //     filigrane était 0 → écart 1 (la séquence 1, transitoire, sautée) ;
        // (2) ph-hub-simulation : l'étape 3 a mis la séquence 6 en lettre
        //     morte (rejet définitif) — jamais confirmée ; l'acquittement de
        //     la séquence 7 révèle le trou (filigrane 5, écart 1).
        List<Map<String, Object>> trous = JsonPath.read(rapport, "$.trous_detectes");
        assertThat(trous).hasSize(2);
        assertThat(trous.get(0))
                .containsEntry("destination", DESTINATION_SIM)
                .containsEntry("sequence", 7)
                .containsEntry("filigrane", 5)
                .containsEntry("ecart", 1);
        assertThat(trous.get(1))
                .containsEntry("destination", DESTINATION_TROU)
                .containsEntry("sequence", 2)
                .containsEntry("filigrane", 0)
                .containsEntry("ecart", 1);

        // La séquence 1 (jamais confirmée) attend toujours sa retransmission.
        assertThat(statutDe(DESTINATION_TROU, event1)).isEqualTo("queued");
        assertThat(statutDe(DESTINATION_TROU, event2)).isEqualTo("acked");
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_TROU)).isEqualTo(2);

        // La séquence 1 finit par passer : le filigrane ne RECULE pas
        // (GREATEST : max(2, 1) = 2 — preuve de monotonie).
        changerSimulation(DESTINATION_TROU, event1, "ACK");
        drain();
        assertThat(statutDe(DESTINATION_TROU, event1)).isEqualTo("acked");
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_TROU)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Étape 5 — signature HMAC recalculée en Java sur l'enveloppe API.
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("Signature : HMAC-SHA256 recalculé en Java sur l'enveloppe rendue par l'API")
    void etape5_signatureRecalculee() throws Exception {
        MvcResult resultat = mockMvc.perform(get("/api/v1/hub/messages")
                        .param("destination", DESTINATION_SIM)
                        .param("status", "acked")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn();
        String corps = resultat.getResponse().getContentAsString();

        // Reconstruction INDÉPENDANTE des octets canoniques : clés de
        // premier niveau triées, sérialisation compacte, sous-arbre
        // payload tel quel — aucun appel au code du module.
        JsonNode message = JSON.readTree(corps).path("messages").get(0);
        JsonNode enveloppe = message.path("envelope");
        Map<String, Object> champs = new TreeMap<>();
        enveloppe.fieldNames().forEachRemaining(champ -> champs.put(champ, enveloppe.get(champ)));
        byte[] octetsCanoniqees = JSON.writeValueAsString(champs)
                .getBytes(StandardCharsets.UTF_8);

        // Les 8 champs du protocole, triés.
        assertThat(champs.keySet()).containsExactly("aggregate_id", "destination", "emis_a",
                "event_id", "event_type", "occurred_at", "payload", "sequence");

        // Recalcul du HMAC-SHA256 avec le secret de développement.
        String signatureRecalculee = hmac(octetsCanoniqees, SECRET_DEV);
        assertThat(message.path("signature").asText())
                .isEqualTo(signatureRecalculee)
                .hasSize(64);
    }

    // ------------------------------------------------------------------
    // Étape 6 — relance manuelle d'un message mort : re-queued puis acked.
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("Retry d'un DEAD : queued, attempts=0, puis acquitté — filigrane intact")
    void etape6_retryDUnMort() throws Exception {
        // Le message mort de l'étape 3 (rejet définitif).
        UUID event = jdbc.queryForObject(
                "SELECT event_id FROM hub.message WHERE status = 'dead' "
                        + "AND destination_id = ? LIMIT 1", UUID.class, idDestination(DESTINATION_SIM));
        UUID message = idMessageDe(DESTINATION_SIM, event);

        mockMvc.perform(post("/api/v1/hub/messages/{id}/retry", message))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(message.toString()))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.next_attempt_at").exists());

        // Traçé dans le journal append-only.
        assertThat(entier("SELECT COUNT(*) FROM hub.delivery_log WHERE message_id = ? "
                + "AND outcome = 'RETRY_MANUEL'", message)).isEqualTo(1);

        // Le partenaire accepte enfin : le drain le livre. Le message relancé
        // porte une séquence ANCIENNE (6) alors que le filigrane est à 8 :
        // l'acquittement ne fait pas RECULER le filigrane (GREATEST).
        long filigraneAvantRelance = entier("SELECT watermark FROM hub.destination "
                + "WHERE code = ?", DESTINATION_SIM);
        long sequenceRelancee = Long.parseLong(sequenceDe(DESTINATION_SIM, event));
        assertThat(filigraneAvantRelance).isGreaterThan(sequenceRelancee);
        changerSimulation(DESTINATION_SIM, event, "ACK");
        drain();
        assertThat(statutDe(DESTINATION_SIM, event)).isEqualTo("acked");
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(filigraneAvantRelance);
    }

    // ------------------------------------------------------------------
    // Étape 7 — API : destinations, stats, filtres, gardes RFC 7807.
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("API : destinations avec stats (jamais de secret), filtres, 400/404/409 RFC 7807")
    void etape7_apiEtGardes() throws Exception {
        // Destinations avec statistiques — AUCUN secret jamais.
        MvcResult destinations = mockMvc.perform(get("/api/v1/hub/destinations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'ph-hub-simulation')]").exists())
                .andReturn();
        String corps = destinations.getResponse().getContentAsString();
        assertThat(corps).doesNotContain("secret");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> liste = (List<Map<String, Object>>) (Object) JsonPath.read(corps, "$[*]");
        assertThat(liste).hasSize(3);
        for (Map<String, Object> destination : liste) {
            assertThat(destination).containsKeys("id", "code", "base_url", "actif",
                    "watermark", "stats");
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) destination.get("stats");
            assertThat(stats).containsKeys("queued", "sent", "acked", "dead", "total");
        }

        // Création : doublon → 409 ; URL invalide → 400.
        mockMvc.perform(post("/api/v1/hub/destinations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ph-hub-simulation\",\"base_url\":\"https://x.test\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Destination déjà déclarée"));
        mockMvc.perform(post("/api/v1/hub/destinations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"nouveau\",\"base_url\":\"ftp://interdit.test\"}"))
                .andExpect(status().isBadRequest());

        // Filtres : statut inconnu → 400, destination inconnue → 400,
        // limit hors bornes → 400.
        mockMvc.perform(get("/api/v1/hub/messages").param("status", "perdu"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/hub/messages").param("destination", "inconnue"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/hub/messages").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/hub/messages").param("limit", "999"))
                .andExpect(status().isBadRequest());

        // Retry : message inconnu → 404 ; message non mort → 409.
        mockMvc.perform(post("/api/v1/hub/messages/{id}/retry", UuidV7.next()))
                .andExpect(status().isNotFound());
        UUID acke = jdbc.queryForObject(
                "SELECT id FROM hub.message WHERE status = 'acked' LIMIT 1", UUID.class);
        mockMvc.perform(post("/api/v1/hub/messages/{id}/retry", acke))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Message non mort"));

        // La garde append-only du journal : UPDATE interdit (style V3).
        UUID message = idMessageDe(DESTINATION_SIM, jdbc.queryForObject(
                "SELECT event_id FROM hub.message WHERE destination_id = ? "
                        + "ORDER BY created_at LIMIT 1", UUID.class, idDestination(DESTINATION_SIM)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE hub.delivery_log SET detail = 'falsifie' "
                                + "WHERE message_id = ?", message))
                .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private static String hmac(byte[] octets, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(octets));
    }
}
