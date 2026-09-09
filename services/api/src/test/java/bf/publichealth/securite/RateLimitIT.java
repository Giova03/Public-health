package bf.publichealth.securite;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration rate-limiting Bucket4j — épique E5.
 *
 * <p>Contexte avec un quota volontairement minuscule (3 requêtes /
 * 60 s, via propriétés de test) : la 4e requête tombe en 429 problem+json
 * avec en-tête Retry-After. Les requêtes hors /api/v1/** ne comptent pas
 * (sonde d'infra) et X-Forwarded-For sépare les compartiments (premier
 * saut = client originel derrière le proxy national).</p>
 *
 * <p>Chaque test travaille sur SON compartiment IP (X-Forwarded-For
 * dédié) : l'ordre d'exécution des méthodes ne peut pas faire déborder
 * le quota d'un test sur l'autre.</p>
 *
 * <p>PostgreSQL : Testcontainers en CI (Docker), zonky embarqué sinon —
 * le contexte applicatif doit démarrer normalement malgré le quota.</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "securite.ratelimit.requests=3",
        "securite.ratelimit.fenetre-seconds=60"
})
@AutoConfigureMockMvc
class RateLimitIT {

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

    @Test
    @DisplayName("quota 3/60 s : 4e requête en 429 problem+json avec Retry-After")
    void quotaPuisRefus() throws Exception {
        // Compartiment de CE test : IP du client de test (127.0.0.1, sans XFF).
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(get("/api/v1/meta"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Trop de requêtes"))
                .andExpect(header().exists("Retry-After"))
                // Repli conservateur : délai moyen entre deux jetons (60 s / 3).
                .andExpect(header().string("Retry-After", "20"));
    }

    @Test
    @DisplayName("hors /api/v1/** : la sonde d'infra ne consomme pas le quota")
    void sondeInfraHorsQuota() throws Exception {
        // Compartiment de CE test : IP 198.51.100.2 (premier saut du proxy).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/meta").header("X-Forwarded-For", "198.51.100.2"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/meta").header("X-Forwarded-For", "198.51.100.2"))
                .andExpect(status().isTooManyRequests());
        // L'actuator n'est pas sous quota : il répond même compartiment épuisé.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X-Forwarded-For : chaque IP cliente a son compartiment")
    void xForwardedForSepareLesCompartiments() throws Exception {
        // Épuise le compartiment de l'IP 198.51.100.3 (premier saut du proxy).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/meta").header("X-Forwarded-For", "198.51.100.3"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/meta").header("X-Forwarded-For", "198.51.100.3"))
                .andExpect(status().isTooManyRequests());
        // Une autre IP cliente repart à zéro : seul le PREMIER saut compte.
        mockMvc.perform(get("/api/v1/meta").header("X-Forwarded-For", "203.0.113.9, 196.0.0.9"))
                .andExpect(status().isOk());
    }
}
