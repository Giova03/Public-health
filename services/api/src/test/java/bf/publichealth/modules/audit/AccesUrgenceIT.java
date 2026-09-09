package bf.publichealth.modules.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import bf.publichealth.modules.audit.domain.AccesUrgence;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration break-the-glass — épique E5.
 *
 * <p>Couverture : création 201 (userId null documenté — posture Sprint 0
 * sans JWT), raison trop courte → 400, fenêtre expirée → l'accès
 * apparaît expiré dans la lecture, file d'attente de revue, examen a
 * posteriori, chaînage du journal d'audit (BREAK_THE_GLASS /
 * BREAK_THE_GLASS_REVIEW scellés sur le maillon précédent), 404/400
 * locaux (RFC 7807).</p>
 *
 * <p>PostgreSQL : Testcontainers quand Docker est présent (CI), PostgreSQL
 * embarqué zonky sinon (poste de développement sans Docker).</p>
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class AccesUrgenceIT {

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

    private ResultActions ouvrir(UUID patientId, String raison) throws Exception {
        return mockMvc.perform(post("/api/v1/audit/break-the-glass")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"%s\",\"reason\":\"%s\"}"
                        .formatted(patientId, raison)));
    }

    private String dernierHash() {
        List<String> hashes = jdbc.queryForList(
                "SELECT hash FROM audit.entry ORDER BY id DESC LIMIT 1", String.class);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    // ------------------------------------------------------------------
    // Création + audit chaîné
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ouverture : 201, fenêtre de 30 minutes, userId null documenté, audit chaîné")
    void ouvertureTraceEtChaine() throws Exception {
        UUID patientId = UUID.randomUUID();
        String hashPrecedent = dernierHash();

        String corps = ouvrir(patientId, "Urgence vitale — consultation sans dossier disponible")
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.patientId").value(patientId.toString()))
                // Posture Sprint 0 (JWT inactif) : userId null, documenté.
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.openedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();

        // Fenêtre : ouverture + 30 minutes exactement.
        Instant ouvert = Instant.parse(JsonPath.read(corps, "$.openedAt"));
        Instant expire = Instant.parse(JsonPath.read(corps, "$.expiresAt"));
        assertThat(java.time.Duration.between(ouvert, expire).toMinutes()).isEqualTo(30);

        // Entrée d'audit chaînée : elle scelle le maillon précédent.
        UUID idAcces = UUID.fromString(JsonPath.read(corps, "$.id"));
        Map<String, Object> entree = jdbc.queryForMap(
                "SELECT actor_id, action, entity, entity_id, reason, prev_hash, result "
                        + "FROM audit.entry WHERE entity_id = ?", idAcces);
        assertThat(entree.get("action")).isEqualTo("BREAK_THE_GLASS");
        assertThat(entree.get("entity")).isEqualTo("emergency_access");
        assertThat(entree.get("reason")).isEqualTo(
                "Urgence vitale — consultation sans dossier disponible");
        assertThat(entree.get("result")).isEqualTo("SUCCESS");
        assertThat(entree.get("actor_id")).isEqualTo(AccesUrgence.UTILISATEUR_ANONYME);
        assertThat(entree.get("prev_hash")).isEqualTo(hashPrecedent);
    }

    @Test
    @DisplayName("raison trop courte : 400 problem+json")
    void raisonTropCourteRefusee() throws Exception {
        // « trop bref » = 9 caractères : sous le minimum de 10.
        ouvrir(UUID.randomUUID(), "trop bref")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ------------------------------------------------------------------
    // Lecture : file patient, expiration, attente de revue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("lecture : file du patient, fenêtre passée = accès expiré, attente de revue")
    void lectureEtExpiration() throws Exception {
        UUID patientId = UUID.randomUUID();
        String corps = ouvrir(patientId, "Accès d'urgence pour prise en charge immédiate")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID idAcces = UUID.fromString(JsonPath.read(corps, "$.id"));

        // Frais : la fenêtre de 30 minutes est ouverte.
        mockMvc.perform(get("/api/v1/audit/emergency-access").param("patientId", patientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idAcces.toString()))
                .andExpect(jsonPath("$[0].expire").value(false))
                .andExpect(jsonPath("$[0].reviewed").value(false))
                .andExpect(jsonPath("$[0].userId").doesNotExist());

        // La file d'attente des non-revus contient la brèche.
        mockMvc.perform(get("/api/v1/audit/emergency-access").param("pending", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].reason".formatted(idAcces),
                        "Accès d'urgence pour prise en charge immédiate").exists());

        // Fenêtre PASSÉE : l'accès expiré apparaît expiré dans la lecture.
        jdbc.update("UPDATE audit.emergency_access SET expires_at = now() - interval '1 hour' "
                + "WHERE id = ?", idAcces);
        mockMvc.perform(get("/api/v1/audit/emergency-access").param("patientId", patientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idAcces.toString()))
                .andExpect(jsonPath("$[0].expire").value(true));
    }

    // ------------------------------------------------------------------
    // Revue a posteriori
    // ------------------------------------------------------------------

    @Test
    @DisplayName("revue : 200, marque reviewed, quitte la file, audit chaîné")
    void revueMarqueEtTrace() throws Exception {
        UUID patientId = UUID.randomUUID();
        String corps = ouvrir(patientId, "Détresse respiratoire — service de garde de nuit")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID idAcces = UUID.fromString(JsonPath.read(corps, "$.id"));

        mockMvc.perform(post("/api/v1/audit/emergency-access/{id}/review", idAcces)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Légitime — urgence validée par le garde\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(idAcces.toString()))
                .andExpect(jsonPath("$.reviewed").value(true))
                .andExpect(jsonPath("$.reviewComment").value("Légitime — urgence validée par le garde"))
                .andExpect(jsonPath("$.reviewedAt").exists());

        // La brèche revue quitte la file d'attente.
        mockMvc.perform(get("/api/v1/audit/emergency-access").param("pending", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(idAcces)).isEmpty());

        // La revue porte SA propre entrée d'audit chaînée.
        Integer revues = jdbc.queryForObject(
                "SELECT count(*) FROM audit.entry WHERE entity_id = ? AND action = 'BREAK_THE_GLASS_REVIEW'",
                Integer.class, idAcces);
        assertThat(revues).isEqualTo(1);
    }

    @Test
    @DisplayName("revue d'un accès inconnu : 404 problem+json")
    void revueInconnueRefusee() throws Exception {
        mockMvc.perform(post("/api/v1/audit/emergency-access/{id}/review", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"n'importe quoi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Accès d'urgence inconnu"));
    }

    @Test
    @DisplayName("identifiant de chemin invalide : 400 problem+json")
    void identifiantInvalideRefuse() throws Exception {
        mockMvc.perform(post("/api/v1/audit/emergency-access/{id}/review", "pas-un-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"commentaire\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
