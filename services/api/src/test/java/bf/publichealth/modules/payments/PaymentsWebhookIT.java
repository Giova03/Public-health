package bf.publichealth.modules.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import bf.publichealth.common.UuidV7;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration de bout en bout — la preuve que le socle tient.
 *
 * <p>Couverture : initiation idempotente, webhook HMAC signé, déduplication
 * par eventId, forward-only (409 sur rétrogradation), garde append-only SQL
 * (V3), unicité des identifiants nationaux (V1), chaînage du journal
 * d'audit (V5), exposition des 15 modules.</p>
 *
 * <p>PostgreSQL : Testcontainers quand Docker est présent (CI), PostgreSQL
 * embarqué zonky sinon (poste de développement sans Docker).</p>
 */
@Tag("integration")
@SpringBootTest(properties = "fedapay.webhook-secret=secret-de-test")
@AutoConfigureMockMvc
class PaymentsWebhookIT {

    private static final String SECRET = "secret-de-test";

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

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private static String signature(String charge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + java.util.HexFormat.of()
                    .formatHex(mac.doFinal(charge.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC indisponible", e);
        }
    }

    private String creerPaiement(String cleIdempotence, String referenceFournisseur) throws Exception {
        String corps = "{\"invoiceId\":\"%s\",\"amount\":2500,\"providerRef\":\"%s\"}"
                .formatted(UUID.randomUUID(), referenceFournisseur);
        String reponse = mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", cleIdempotence)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("INITIATED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(reponse, "$.id");
    }

    private ResultActions webhook(String eventId, String reference, String statut) throws Exception {
        String corps = "{\"reference\":\"%s\",\"status\":\"%s\"}".formatted(reference, statut);
        return mockMvc.perform(post("/api/v1/webhooks/fedapay")
                .header("X-Event-Id", eventId)
                .header("X-FedaPay-Signature", signature(corps))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corps));
    }

    // ------------------------------------------------------------------
    // Cycle de vie du paiement (scénarios critiques E4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Initiation idempotente + transitions signées + rétrogradation refusée (409)")
    void cycleDeVieComplet() throws Exception {
        String reference = "FED-IT-CYCLE";
        String id = creerPaiement("cle-cycle", reference);

        // Rejeu réseau : 200, le MÊME paiement, jamais un doublon.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "cle-cycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":\"%s\",\"amount\":2500,\"providerRef\":\"%s\"}"
                                .formatted(UUID.randomUUID(), reference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // Chemin nominal : PENDING → AUTHORIZED → SUCCEEDED.
        webhook("evt-cycle-1", reference, "pending")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        webhook("evt-cycle-2", reference, "authorized")
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        webhook("evt-cycle-3", reference, "succeeded")
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        // Un état ne recule JAMAIS : 409, historique intact.
        webhook("evt-cycle-4", reference, "pending")
                .andExpect(status().isConflict());

        // Fournisseur qui réessaie le même eventId : acquitté, aucune seconde action.
        webhook("evt-cycle-3", reference, "succeeded")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATED"));

        mockMvc.perform(get("/api/v1/payments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));

        UUID uuid = UUID.fromString(id);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments.payment_transition WHERE payment_id = ?",
                Long.class, uuid)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payments.payment WHERE id = ?",
                String.class, uuid)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("Signature invalide : 401 générique, aucune action")
    void signatureInvalide() throws Exception {
        String reference = "FED-IT-SIGNATURE";
        creerPaiement("cle-signature", reference);

        mockMvc.perform(post("/api/v1/webhooks/fedapay")
                        .header("X-Event-Id", "evt-signature-invalide")
                        .header("X-FedaPay-Signature", "sha256=deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"%s\",\"status\":\"pending\"}".formatted(reference)))
                .andExpect(status().isUnauthorized());

        // Absence totale de signature : même refus.
        mockMvc.perform(post("/api/v1/webhooks/fedapay")
                        .header("X-Event-Id", "evt-signature-absente")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"%s\",\"status\":\"pending\"}".formatted(reference)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook orphelin : consigné, acquitté, aucun crash")
    void webhookOrphelin() throws Exception {
        webhook("evt-orphelin", "REFERENCE-INEXISTANTE", "succeeded")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORPHAN"));
    }

    @Test
    @DisplayName("Statut fournisseur inconnu : rejeté proprement")
    void statutInconnu() throws Exception {
        String reference = "FED-IT-BANANA";
        creerPaiement("cle-banana", reference);
        webhook("evt-banana", reference, "banana")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ------------------------------------------------------------------
    // Gardes SQL (migrations V1, V3, V5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V3 : une observation clinique ne se réécrit jamais (append-only)")
    void gardeAppendOnly() {
        UUID patient = UuidV7.next();
        UUID encounter = UuidV7.next();
        UUID observation = UuidV7.next();
        jdbc.update("""
                INSERT INTO clinical.encounter (id, patient_id, facility_id, encounter_class, started_at)
                VALUES (?, ?, ?, 'field', now())""",
                encounter, patient, UuidV7.next());
        jdbc.update("""
                INSERT INTO clinical.observation (id, encounter_id, patient_id, code, value_num, effective_at)
                VALUES (?, ?, ?, 'TA_SYST', 120, now())""",
                observation, encounter, patient);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE clinical.observation SET value_num = 121 WHERE id = ?", observation))
                .isInstanceOf(DataAccessException.class);

        // La seule mutation légale : l'annulation explicite, tracée.
        jdbc.update("UPDATE clinical.observation SET status = 'entered-in-error' WHERE id = ?",
                observation);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM clinical.observation WHERE id = ?",
                String.class, observation)).isEqualTo("entered-in-error");

        // Et une fois annulée, plus rien ne bouge.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE clinical.observation SET status = 'final' WHERE id = ?", observation))
                .isInstanceOf(DataAccessException.class);

        // L'historique clinique ne se supprime pas non plus.
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM clinical.observation WHERE id = ?", observation))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("V1 : un identifiant national (NUNP, CNIB) n'existe qu'une fois")
    void identifiantNationalUnique() {
        UUID patient1 = UuidV7.next();
        UUID patient2 = UuidV7.next();
        jdbc.update("INSERT INTO identity.patient (id) VALUES (?)", patient1);
        jdbc.update("INSERT INTO identity.patient (id) VALUES (?)", patient2);

        jdbc.update("""
                INSERT INTO identity.patient_identifier (id, patient_id, system, value)
                VALUES (?, ?, 'NUNP', 'BF-000001')""",
                UuidV7.next(), patient1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO identity.patient_identifier (id, patient_id, system, value)
                VALUES (?, ?, 'NUNP', 'BF-000001')""",
                UuidV7.next(), patient2))
                .isInstanceOf(DataAccessException.class);

        // LOCAL reste duplicable : chaque structure sanitaire garde son registre.
        jdbc.update("""
                INSERT INTO identity.patient_identifier (id, patient_id, system, value)
                VALUES (?, ?, 'LOCAL', 'SN-000077')""",
                UuidV7.next(), patient2);
    }

    @Test
    @DisplayName("V5 : le journal d'audit est chaîné — chaque entrée scelle la précédente")
    void chaineDAuditIntacte() throws Exception {
        // L'ordre d'exécution des tests n'étant pas garanti, on déclenche
        // d'abord des actions auditées (création + transition signée).
        String reference = "FED-IT-AUDIT-" + UUID.randomUUID();
        creerPaiement("cle-audit-" + UUID.randomUUID(), reference);
        webhook("evt-audit-" + UUID.randomUUID(), reference, "pending")
                .andExpect(status().isOk());

        List<Map<String, Object>> entrees = jdbc.queryForList(
                "SELECT prev_hash, hash FROM audit.entry ORDER BY id");
        assertThat(entrees).isNotEmpty();
        for (int i = 1; i < entrees.size(); i++) {
            assertThat(entrees.get(i).get("prev_hash"))
                    .as("maillon %d", i)
                    .isEqualTo(entrees.get(i - 1).get("hash"));
        }
    }

    // ------------------------------------------------------------------
    // Carte de la plateforme
    // ------------------------------------------------------------------

    @Test
    @DisplayName("L'API déclare elle-même ses 15 modules")
    void metaExposeLes15Modules() throws Exception {
        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules", hasSize(15)))
                .andExpect(jsonPath("$.application").value("public-health-api"));
    }
}
