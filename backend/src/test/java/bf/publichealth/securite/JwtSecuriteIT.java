package bf.publichealth.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration JWT Supabase — épique E5, la vérification RÉELLE.
 *
 * <p>Contexte démarré avec {@code securite.jwt.actif=true} et un secret
 * HS256 de test (44 caractères : Nimbus exige ≥ 256 bits). Les JWS sont
 * construits À LA MAIN (Mac HS256 + Base64URL, zéro dépendance nouvelle) :
 * header {"alg":"HS256"}, claims sub/exp/app_role|roles.</p>
 *
 * <p>Couverture : sans jeton → 401 problem+json sur /api/v1/** et
 * /fhir/** (l'uplink machine /api/v1/sync aussi — P0 : les clients
 * machine portent un jeton de service) ; la sonde /actuator/health
 * reste OUVERTE même JWT actif (contrat CI/infra) ; jeton valide → 200
 * et GUC PostgreSQL app.user_id + app.roles posées sur la connexion de
 * la requête (preuve bout-en-bout du pont sub/rôles → RLS V5/V10) ;
 * jeton expiré → 401 ; claim app_role/roles → ROLE_ADMIN requis par
 * /api/v1/admin/** (200 admin, 403 rôle insuffisant) ; break-the-glass
 * propage l'identité (userId = sub).</p>
 *
 * <p>La sonde n'existe QUE dans ce test (@Import) : la configuration de
 * production n'est pas polluée.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "securite.jwt.actif=true",
        "securite.jwt.secret=secret-hs256-de-test-public-health-0123456789"
})
@AutoConfigureMockMvc
@Import(JwtSecuriteIT.SondeSecurite.class)
class JwtSecuriteIT {

    /** ≥ 32 caractères — Nimbus refuse les clés HS256 plus courtes (256 bits). */
    private static final String SECRET = "secret-hs256-de-test-public-health-0123456789";

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
    // Sonde test-only : prouve la GUC et le rôle SUR la requête courante
    // ------------------------------------------------------------------

    /**
     * Sonde fictive (importée uniquement ici). /api/v1/securite/sonde exige
     * une authentification et retourne la GUC PostgreSQL réellement posée
     * sur la connexion de la requête — c'est la preuve pont JWT → RLS.
     * /api/v1/admin/sonde exige ROLE_ADMIN (convention P0 de SecurityConfig).
     */
    @RestController
    static class SondeSecurite {

        private final JdbcTemplate jdbc;

        SondeSecurite(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @GetMapping("/api/v1/securite/sonde")
        public Map<String, Object> sonde(Authentication auth) {
            // Lecture sur LA connexion de la requête : si le filtre + le
            // décorateur DataSource ont fait leur travail, app.user_id = sub
            // et app.roles = rôles du jeton.
            String gucUtilisateur = jdbc.queryForObject(
                    "SELECT nullif(current_setting('app.user_id', true), '')", String.class);
            String gucRoles = jdbc.queryForObject(
                    "SELECT nullif(current_setting('app.roles', true), '')", String.class);
            Map<String, Object> sonde = new HashMap<>();
            sonde.put("sujet", auth.getName());
            sonde.put("guc", gucUtilisateur);
            sonde.put("gucRoles", gucRoles);
            sonde.put("roles", auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).toList());
            return sonde;
        }

        @GetMapping("/api/v1/admin/sonde")
        public Map<String, Object> sondeAdmin(Authentication auth) {
            return Map.of("sujet", auth.getName());
        }
    }

    // ------------------------------------------------------------------
    // Forge de JWS HS256 — à la main, zéro dépendance nouvelle
    // ------------------------------------------------------------------

    private static String jeton(Map<String, Object> claims) {
        try {
            StringBuilder json = new StringBuilder("{");
            boolean premier = true;
            for (Map.Entry<String, Object> claim : claims.entrySet()) {
                if (!premier) {
                    json.append(',');
                }
                premier = false;
                json.append('"').append(claim.getKey()).append("\":");
                Object valeur = claim.getValue();
                if (valeur instanceof Number nombre) {
                    json.append(nombre);
                } else if (valeur instanceof Iterable<?> liste) {
                    json.append('[');
                    boolean premierRole = true;
                    for (Object role : liste) {
                        if (!premierRole) {
                            json.append(',');
                        }
                        premierRole = false;
                        json.append('"').append(role).append('"');
                    }
                    json.append(']');
                } else {
                    json.append('"').append(valeur).append('"');
                }
            }
            json.append('}');

            String entete = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8));
            String charge = base64Url(json.toString().getBytes(StandardCharsets.UTF_8));
            String aSigner = entete + "." + charge;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = base64Url(mac.doFinal(aSigner.getBytes(StandardCharsets.UTF_8)));
            return aSigner + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Forge JWS HS256 indisponible", e);
        }
    }

    private static String base64Url(byte[] octets) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }

    private static String jetonValide(String sujet, String appRole) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", sujet);
        claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        claims.put("iat", Instant.now().getEpochSecond());
        if (appRole != null) {
            claims.put("app_role", appRole);
        }
        return jeton(claims);
    }

    private ResultActions avecJeton(String jeton, String uri) throws Exception {
        return mockMvc.perform(get(uri).header("Authorization", "Bearer " + jeton));
    }

    // ------------------------------------------------------------------
    // 401 — sans jeton, jeton expiré ; posture P0 : aucune exception
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sans jeton : 401 problem+json ; /actuator/health reste OUVERT (contrat CI)")
    void sansJetonRefuse() throws Exception {
        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Authentification requise"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        // P0 : aucune exception MÉTIER — l'uplink machine /api/v1/sync
        // s'authentifie comme le reste de l'API.
        mockMvc.perform(get("/api/v1/sync/delta"))
                .andExpect(status().isUnauthorized());
        // La sonde d'infra reste OUVERTE même JWT actif : le contrat
        // CI/répartiteur de charge ne dépend pas de la posture sécurité.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("jeton expiré : 401 problem+json (dérive d'horloge 60 s dépassée)")
    void jetonExpireRefuse() throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", UUID.randomUUID());
        claims.put("exp", Instant.now().minusSeconds(3600).getEpochSecond());
        ResultActions reponse = avecJeton(jeton(claims), "/api/v1/meta");
        reponse.andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ------------------------------------------------------------------
    // 200 — jeton valide + GUC posée (preuve pont JWT → RLS V10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("jeton valide : 200, GUC app.user_id + app.roles posées sur la connexion de la requête")
    void jetonValidePoserLaGuc() throws Exception {
        String sujet = UUID.randomUUID().toString();
        String corps = avecJeton(jetonValide(sujet, "soignant"), "/api/v1/securite/sonde")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sujet").value(sujet))
                // Autorités Spring en MAJUSCULES (convention hasRole).
                .andExpect(jsonPath("$.roles[0]").value("ROLE_SOIGNANT"))
                .andReturn().getResponse().getContentAsString();
        // PREUVE du pont JWT → RLS : la GUC user_id est le sub sur LA
        // connexion de la requête, et app.roles porte le rôle du jeton.
        assertThat((String) JsonPath.read(corps, "$.guc")).isEqualTo(sujet);
        assertThat((String) JsonPath.read(corps, "$.gucRoles")).isEqualTo("soignant");
    }

    @Test
    @DisplayName("jeton valide : l'endpoint métier existant répond 200")
    void jetonValideEndpointMetier() throws Exception {
        avecJeton(jetonValide(UUID.randomUUID().toString(), null), "/api/v1/meta")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("public-health-api"));
    }

    // ------------------------------------------------------------------
    // 403/200 — rôle ADMIN depuis app_role, puis depuis roles (tableau)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claim app_role=admin : accès à l'endpoint admin (convention P0)")
    void roleAdminDepuisAppRole() throws Exception {
        avecJeton(jetonValide(UUID.randomUUID().toString(), "admin"), "/api/v1/admin/sonde")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("rôle insuffisant : 403 problem+json")
    void roleInsuffisantRefuse() throws Exception {
        avecJeton(jetonValide(UUID.randomUUID().toString(), "soignant"), "/api/v1/admin/sonde")
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Accès refusé"));
    }

    @Test
    @DisplayName("claim roles=[admin] (tableau) : autorité ROLE_ADMIN reconnue")
    void roleAdminDepuisClaimRoles() throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        claims.put("roles", java.util.List.of("admin"));
        avecJeton(jeton(claims), "/api/v1/admin/sonde")
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Propagation d'identité métier : break-the-glass porte userId = sub
    // ------------------------------------------------------------------

    @Test
    @DisplayName("break-the-glass avec JWT : userId = sub propagé (audit E2E)")
    void breakTheGlassPropageIdentite() throws Exception {
        String sujet = UUID.randomUUID().toString();
        UUID patientId = UUID.randomUUID();
        String corps = mockMvc.perform(post("/api/v1/audit/break-the-glass")
                        .header("Authorization", "Bearer " + jetonValide(sujet, "medecin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"reason\":\"Urgence vitale, contexte authentifié\"}"
                                .formatted(patientId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(sujet))
                .andReturn().getResponse().getContentAsString();

        UUID idAcces = UUID.fromString(JsonPath.read(corps, "$.id"));
        Integer traces = jdbc.queryForObject(
                "SELECT count(*) FROM audit.entry WHERE entity_id = ? AND action = 'BREAK_THE_GLASS' "
                        + "AND actor_id = ?",
                Integer.class, idAcces, UUID.fromString(sujet));
        assertThat(traces).isEqualTo(1);
    }
}
