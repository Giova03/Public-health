package bf.publichealth.observabilite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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

import bf.publichealth.config.observabilite.ObservabiliteGauges;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration observabilité — épique E8, POSTURE PAR DÉFAUT (JWT Sprint 0
 * inactif, comme la CI de non-régression).
 *
 * <p>Couverture : /actuator/prometheus répond (exposition programmatique
 * par {@code ExpositionPrometheusEnvironnement} — application.yml
 * INTOUCHABLE) et porte les métriques métier {@code ph_*} avec des VALEURS
 * NUMÉRIQUES ; /actuator/health agrège les sondes {@code phOutbox} et
 * {@code phBase} (composants visibles) ; le seed d'un événement outbox
 * fait passer la gauge au-dessus de zéro après refresh MANUEL (pas
 * d'attente du cron 60 s) ; un retard d'outbox d'une heure fait passer
 * {@code phOutbox} DOWN → santé globale 503 (l'alerte « health DOWN »).</p>
 *
 * <p><b>@AutoConfigureObservability</b> : Spring Boot désactive par défaut
 * l'export des métriques DANS LES TESTS (DisableObservabilityContextCustomizer
 * pose {@code management.defaults.metrics.export.enabled=false}) — en
 * production les exports sont actifs sans rien configurer. L'annotation
 * rétablit ici le comportement production pour prouver le scrape.</p>
 *
 * <p>PostgreSQL : Testcontainers en CI (Docker), zonky embarqué sinon —
 * même patron que JwtSecuriteIT/RateLimitIT.</p>
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureObservability
@AutoConfigureMockMvc
class ObservabiliteIT {

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

    @Autowired
    private ObservabiliteGauges gauges;

    // ------------------------------------------------------------------
    // /actuator/prometheus — exposition programmatique (EPP, yml intact)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scrape Prometheus : 200, format texte, métriques ph_* avec valeurs numériques")
    void prometheusExposeLesMetriques() throws Exception {
        // Mesure explicite (comme le fait afterSingletonsInstantiated au
        // démarrage) : la preuve ne dépend ni du cron 60 s ni d'une course
        // avec le scheduler.
        gauges.rafraichir();
        String corps = corpsPrometheus();
        // Le registre Prometheus est monté (métriques JVM fournies par Boot).
        assertThat(corps).contains("jvm_memory_used_bytes");

        // Métriques métier E8 : les noms exacts livrés au dashboard Grafana
        // (« ph_sync_ops » / « ph_paiements » : le client Prometheus réserve le
        // suffixe _total aux compteurs — cf. ObservabiliteGauges).
        assertThat(corps).contains("# TYPE ph_sync_outbox_en_attente gauge");
        assertThat(corps).contains("# TYPE ph_sync_outbox_retard_secondes gauge");
        assertThat(corps).contains("# TYPE ph_sync_ops gauge");
        assertThat(corps).contains("# TYPE ph_paiements gauge");
        assertThat(corps).contains("# TYPE ph_identity_file_revue gauge");
        assertThat(corps).contains("# TYPE ph_reconciliation_derniere_age_secondes gauge");

        // Chaque série ph_* porte une valeur NUMÉRIQUE scrappable.
        assertThat(valeur(corps, "ph_sync_outbox_en_attente")).isNotNaN();
        assertThat(valeur(corps, "ph_sync_outbox_retard_secondes")).isEqualTo(0.0);
        assertThat(valeur(corps, "ph_identity_file_revue")).isEqualTo(0.0);
        // 3 séries taggées result (CHECK V4) — toutes numériques.
        for (String resultat : new String[]{"APPLIED", "REJECTED", "CONFLICT"}) {
            assertThat(valeur(corps, "ph_sync_ops{result=\"" + resultat + "\"}"))
                    .isEqualTo(0.0);
        }
        // 8 séries taggées statut (CHECK V2) — toutes numériques.
        for (String statut : new String[]{"INITIATED", "PENDING", "AUTHORIZED", "SUCCEEDED",
                "FAILED", "CANCELLED", "REFUNDED", "RECONCILED"}) {
            assertThat(valeur(corps, "ph_paiements{statut=\"" + statut + "\"}"))
                    .isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("seed d'un événement outbox : gauge > 0 après refresh manuel (sans attendre le cron)")
    void gaugeOutboxApresSeedEtRafraichissement() throws Exception {
        UUID evenement = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO sync.outbox
                        (event_id, event_type, aggregate_id, payload, occurred_at, published)
                    VALUES (?, 'PATIENT_CREE', ?, '{}'::jsonb,
                            now() - interval '10 minutes', false)
                    """, evenement, UUID.randomUUID());
            // Refresh MANUEL : la preuve ne dépend pas du cron 60 s.
            gauges.rafraichir();

            String corps = corpsPrometheus();
            assertThat(valeur(corps, "ph_sync_outbox_en_attente")).isEqualTo(1.0);
            // L'événement a 10 minutes : le retard doit les refléter.
            assertThat(valeur(corps, "ph_sync_outbox_retard_secondes")).isGreaterThan(300.0);
        } finally {
            jdbc.update("DELETE FROM sync.outbox WHERE event_id = ?", evenement);
        }
    }

    @Test
    @DisplayName("seed d'un paiement PENDING : la série taggée statut passe à 1")
    void gaugePaiementsParStatutApresSeed() throws Exception {
        UUID paiement = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO payments.payment
                        (id, invoice_id, amount, state, idempotency_key)
                    VALUES (?, ?, 2500, 'PENDING', ?)
                    """, paiement, UUID.randomUUID(), "it-observabilite-" + paiement);
            gauges.rafraichir();

            String corps = corpsPrometheus();
            assertThat(valeur(corps, "ph_paiements{statut=\"PENDING\"}"))
                    .isEqualTo(1.0);
            assertThat(valeur(corps, "ph_paiements{statut=\"INITIATED\"}"))
                    .isEqualTo(0.0);
        } finally {
            jdbc.update("DELETE FROM payments.payment WHERE id = ?", paiement);
        }
    }

    // ------------------------------------------------------------------
    // /actuator/health — sondes phOutbox / phBase
    // ------------------------------------------------------------------

    @Test
    @DisplayName("santé : status UP avec les composants phOutbox et phBase présents")
    void santeAvecComposantsObservabilite() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.phOutbox.status").value("UP"))
                .andExpect(jsonPath("$.components.phBase.status").value("UP"));
    }

    @Test
    @DisplayName("retard d'outbox > 3600 s : phOutbox DOWN, santé globale 503 (alerte health DOWN)")
    void sondeOutboxDownAuDelaDuSeuilDeRetard() throws Exception {
        UUID evenement = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO sync.outbox
                        (event_id, event_type, aggregate_id, payload, occurred_at, published)
                    VALUES (?, 'PATIENT_CREE', ?, '{}'::jsonb,
                            now() - interval '3 hours', false)
                    """, evenement, UUID.randomUUID());

            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components.phOutbox.status").value("DOWN"))
                    // phBase reste sain : c'est l'outbox qui traîne, pas la base.
                    .andExpect(jsonPath("$.components.phBase.status").value("UP"));
        } finally {
            jdbc.update("DELETE FROM sync.outbox WHERE event_id = ?", evenement);
        }
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    private String corpsPrometheus() throws Exception {
        MvcResult resultat = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn();
        return resultat.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Extrait la valeur numérique de la série (ligne « nom value » du scrape). */
    private static double valeur(String corps, String serie) {
        Matcher correspondance = Pattern.compile("(?m)^" + Pattern.quote(serie) + "\\s+(\\S+)$")
                .matcher(corps);
        if (!correspondance.find()) {
            throw new AssertionError("série absente du scrape : " + serie + "\n" + corps);
        }
        return Double.parseDouble(correspondance.group(1));
    }
}
