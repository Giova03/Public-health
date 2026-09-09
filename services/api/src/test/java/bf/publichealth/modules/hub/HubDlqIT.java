package bf.publichealth.modules.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import bf.publichealth.common.UuidV7;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration de la lettre morte (DLQ) du connecteur HUB — contexte et
 * propriétés distincts de {@link HubIT} : la limite de tentatives est
 * ABAISSÉE à 2 ({@code hub.retry.tentatives-max=2}) pour prouver
 * l'épuisement, le backoff est à 0 (retransmissions immédiates), le job
 * planifié est éteint (drainage par POST).
 *
 * <p>Scénario : N échecs transitoires → DEAD + audit DENIED
 * (TENTATIVES_EPUISEES) + journal DLQ_TENTATIVES ; puis relance manuelle
 * (retry) → queued, attempts=0 → acquittement et filigrane.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "hub.drain.actif=false",
        "hub.backoff.base-ms=0",
        "hub.retry.tentatives-max=2"
})
@AutoConfigureMockMvc
@DisplayName("Lettre morte HUB : épuisement des tentatives puis relance manuelle")
class HubDlqIT {

    private static final String DESTINATION_SIM = "ph-hub-simulation";

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

    @Test
    @DisplayName("2 échecs transitoires (limite abaissée à 2) → DEAD + audit ; retry → ACK + filigrane")
    void tentativesEpuiseesPuisRelance() throws Exception {
        // Un événement dont le partenaire échoue toujours (transitoire).
        UUID event = UuidV7.next();
        jdbc.update("INSERT INTO sync.outbox (event_id, event_type, aggregate_id, payload, "
                        + "occurred_at, published) VALUES (?, 'sync.patient.created', ?, "
                        + "?::jsonb, now(), false)",
                event, UuidV7.next(), "{\"ph_reference\":\"PH-DLQ\"}");
        jdbc.update("INSERT INTO hub.simulation_reponse (destination_code, event_id, comportement) "
                + "VALUES (?, ?, 'ECHEC_TRANSITOIRE')", DESTINATION_SIM, event);

        // Tentative 1 : sous la limite → retransmission programmée.
        MvcResult premier = mockMvc.perform(post("/api/v1/hub/drain"))
                .andExpect(status().isOk()).andReturn();
        String rapport1 = premier.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(rapport1, "$.morts")).intValue()).isEqualTo(0);
        assertThat(statut(event)).isEqualTo("queued");
        assertThat(attempts(event)).isEqualTo(1);
        assertThat(entier("SELECT COUNT(*) FROM sync.outbox WHERE event_id = ? AND published",
                event)).isEqualTo(1);

        // Tentative 2 : limite atteinte → lettre morte (DLQ).
        MvcResult second = mockMvc.perform(post("/api/v1/hub/drain"))
                .andExpect(status().isOk()).andReturn();
        String rapport2 = second.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(rapport2, "$.morts")).intValue()).isEqualTo(1);
        assertThat(statut(event)).isEqualTo("dead");
        assertThat(attempts(event)).isEqualTo(2);

        UUID message = idMessage(event);
        // Journal : l'épuisement est tracé, append-only.
        List<Map<String, Object>> journal = jdbc.queryForList(
                "SELECT attempt, outcome FROM hub.delivery_log WHERE message_id = ? ORDER BY attempt",
                message);
        assertThat(journal).hasSize(2);
        assertThat(journal.get(0)).containsEntry("outcome", "ECHEC_TRANSITOIRE");
        assertThat(journal.get(1)).containsEntry("outcome", "DLQ_TENTATIVES");

        // Audit DENIED : la lettre morte est un incident, il est tracé.
        assertThat(entier("SELECT COUNT(*) FROM audit.entry WHERE action = 'HUB_DLQ' "
                        + "AND entity = 'hub_message' AND entity_id = ?", message))
                .isGreaterThanOrEqualTo(1);
        assertThat(entier("SELECT COUNT(*) FROM audit.entry WHERE reason = 'TENTATIVES_EPUISEES' "
                + "AND entity_id = ?", message)).isGreaterThanOrEqualTo(1);

        // Le filigrane n'a PAS bougé : rien n'a été acquitté.
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(0);

        // Relance manuelle : queued, tentatives remises à 0, échéance immédiate.
        mockMvc.perform(post("/api/v1/hub/messages/{id}/retry", message))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.attempts").value(0));

        // Le partenaire se rassit : le drainage livre et acquitte.
        jdbc.update("UPDATE hub.simulation_reponse SET comportement = 'ACK' "
                + "WHERE destination_code = ? AND event_id = ?", DESTINATION_SIM, event);
        MvcResult troisieme = mockMvc.perform(post("/api/v1/hub/drain"))
                .andExpect(status().isOk()).andReturn();
        String rapport3 = troisieme.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(rapport3, "$.acquittes")).intValue()).isEqualTo(1);
        assertThat(statut(event)).isEqualTo("acked");
        assertThat(entier("SELECT watermark FROM hub.destination WHERE code = ?",
                DESTINATION_SIM)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private UUID idDestination() {
        return jdbc.queryForObject("SELECT id FROM hub.destination WHERE code = ?",
                UUID.class, DESTINATION_SIM);
    }

    private UUID idMessage(UUID event) {
        return jdbc.queryForObject(
                "SELECT id FROM hub.message WHERE destination_id = ? AND event_id = ?",
                UUID.class, idDestination(), event);
    }

    private String statut(UUID event) {
        return jdbc.queryForObject(
                "SELECT status FROM hub.message WHERE destination_id = ? AND event_id = ?",
                String.class, idDestination(), event);
    }

    private int attempts(UUID event) {
        return jdbc.queryForObject(
                "SELECT attempts FROM hub.message WHERE destination_id = ? AND event_id = ?",
                Integer.class, idDestination(), event);
    }

    private long entier(String sql, Object... parametres) {
        Long valeur = jdbc.queryForObject(sql, Long.class, parametres);
        return valeur == null ? 0L : valeur;
    }
}
