package bf.publichealth.modules.hub.application;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import bf.publichealth.modules.hub.adapter.transport.TransportHttps;
import bf.publichealth.modules.hub.adapter.transport.TransportSimule;
import bf.publichealth.modules.hub.domain.BackoffExponentiel;
import bf.publichealth.modules.hub.domain.PolitiqueRetry;

/**
 * Configuration locale au module hub — TOUS les défauts vivent DANS LE
 * CODE (application.yml reste intouchable, contrainte d'intégration).
 *
 * <p>Propriétés (avec défaut) :
 * <ul>
 *   <li>{@code hub.secret-destinations} — map code → secret HMAC. DÉFAUT
 *       DANS LE CODE : {@code ph-hub-simulation} → secret de développement
 *       {@code dev-secret-hub-a-changer} (documenté, à remplacer en
 *       production par propriété ou variable d'environnement :
 *       {@code hub.secret-destinations={ph-hub-national:'…',…}}).</li>
 *   <li>{@code hub.transport} — {@code simulation} par défaut ;
 *       {@code https} branche le transport réel (squelette, non testé
 *       en intégration).</li>
 *   <li>{@code hub.transport.simulation.defaut} — comportement du
 *       transport simulé pour un événement non semé dans
 *       {@code hub.simulation_reponse} : {@code ACK} par défaut
 *       (admis : ACK, ECHEC_TRANSITOIRE, REJET_DEFINITIF).</li>
 *   <li>{@code hub.retry.tentatives-max} — 8 par défaut (DLQ).</li>
 *   <li>{@code hub.backoff.base-ms} — 30 000 (30 s) par défaut ;
 *       {@code hub.backoff.plafond-ms} — 3 600 000 (1 h).</li>
 *   <li>{@code hub.drain.actif} (vrai) et {@code hub.drain.cron}
 *       (toutes les 30 s par défaut) — job planifié.</li>
 *   <li>{@code hub.drain.lot-max} — 200 événements/destination par passage.</li>
 *   <li>{@code hub.envoi.delai-reprise-ms} — 300 000 (5 min) : échéance de
 *       reprise d'un départ sans conclusion.</li>
 * </ul></p>
 *
 * <p>{@code @EnableScheduling} local (idempotent) : le module hub
 * ordonnance son propre drainage.</p>
 */
@Configuration
@EnableScheduling
public class HubConfiguration {

    /**
     * Secrets HMAC des destinations. Le défaut DU CODE documente la
     * destination de développement {@code ph-hub-simulation} — le secret
     * de dév n'est JAMAIS journalisé, JAMAIS rendu par l'API.
     */
    @Bean
    public GestionnaireSecrets gestionnaireSecrets(
            @Value("#{${hub.secret-destinations:{'ph-hub-simulation':'dev-secret-hub-a-changer'}}}")
            Map<String, String> secretsConfigures) {
        return new GestionnaireSecrets(secretsConfigures);
    }

    /** Trempe et gigue du protocole : base 30 s ×2^(n-1), gigue 0-50 %, plafond 1 h. */
    @Bean
    public BackoffExponentiel backoffExponentiel(
            @Value("${hub.backoff.base-ms:30000}") long baseMs,
            @Value("${hub.backoff.plafond-ms:3600000}") long plafondMs) {
        return new BackoffExponentiel(Duration.ofMillis(baseMs), Duration.ofMillis(plafondMs),
                BackoffExponentiel.JITTER_MAX_DEFAUT,
                ThreadLocalRandom.current()::nextDouble);
    }

    /** Politique de retransmission : DLQ après 8 tentatives par défaut. */
    @Bean
    public PolitiqueRetry politiqueRetry(
            @Value("${hub.retry.tentatives-max:8}") int tentativesMax) {
        return new PolitiqueRetry(tentativesMax);
    }

    /**
     * Transport HTTPS RÉEL (production) — activé par
     * {@code hub.transport=https} : POST vers base_url (TLS standard),
     * en-têtes {@code X-PH-Signature} + {@code X-PH-Sequence}. Squelette
     * documenté, non testé en intégration (aucune dépendance réseau).
     */
    @Bean
    @ConditionalOnProperty(name = "hub.transport", havingValue = "https")
    public TransportHub transportHttps(
            @Value("${hub.transport.timeout:10s}") Duration timeout) {
        return new TransportHttps(timeout);
    }

    /**
     * Transport SIMULÉ (défaut) — déterministe : le scénario par
     * (destination, event_id) est semé dans {@code hub.simulation_reponse}
     * par le test/dev ; un événement non semé suit le comportement par
     * défaut configurable (ACK en production de dev).
     */
    @Bean
    @ConditionalOnMissingBean(TransportHub.class)
    public TransportHub transportSimule(
            JdbcTemplate jdbc,
            @Value("${hub.transport.simulation.defaut:ACK}") String comportementDefaut) {
        return new TransportSimule(jdbc, comportementDefaut);
    }
}
