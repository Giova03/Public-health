package bf.publichealth.modules.payments.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import bf.publichealth.modules.payments.adapter.fournisseur.FournisseurFedaPayHttp;
import bf.publichealth.modules.payments.adapter.fournisseur.FournisseurSimule;
import bf.publichealth.modules.payments.domain.StatutPrestataire;

/**
 * Configuration locale au module payments (épique E4).
 *
 * <p><b>Scheduling</b> : @EnableScheduling est LOCAL au module — aucun
 * autre module n'ordonnance quoi que ce soit. Le job nocturne vit dans
 * {@link ReconciliationNocturne} (activable/désactivable par
 * {@code paiements.reconciliation.active}, vrai par défaut ; cron
 * {@code paiements.reconciliation.cron}, défaut {@code 0 15 3 * * *}).</p>
 *
 * <p><b>Fournisseur de paiements</b> (port hexagonal) : le squelette
 * HTTP réel est branché si {@code fedapay.api-url} est configuré, sinon
 * le fournisseur simulé sert les tests et le dev. Defaults dans le
 * code — application.yml reste intouchable (contrainte d'intégration).</p>
 */
@Configuration
@EnableScheduling
public class PaiementsConfiguration {

    /**
     * Fournisseur FedaPay HTTP réel — SANS credential : la clé d'API
     * vient de l'environnement ({@code fedapay.api-key}), jamais du code.
     */
    @Bean
    @ConditionalOnProperty("fedapay.api-url")
    public FournisseurPaiements fournisseurFedaPayHttp(
            @Value("${fedapay.api-url}") String apiUrl,
            @Value("${fedapay.api-key:}") String apiKey,
            @Value("${fedapay.timeout:10s}") Duration timeout) {
        return new FournisseurFedaPayHttp(apiUrl, apiKey, timeout);
    }

    /**
     * Fournisseur simulé — défaut déterministe (tests/dev) : statuts
     * semés dans payments.provider_simulation ; référence absente =
     * statut configurable (INCONNU par défaut).
     */
    @Bean
    @ConditionalOnMissingBean(FournisseurPaiements.class)
    public FournisseurPaiements fournisseurSimule(
            JdbcTemplate jdbc,
            @Value("${paiements.reconciliation.fournisseur-simule.sans-entree:INCONNU}")
            String statutSansEntree) {
        StatutPrestataire statut;
        try {
            statut = StatutPrestataire.valueOf(statutSansEntree.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "paiements.reconciliation.fournisseur-simule.sans-entree invalide : "
                            + statutSansEntree, e);
        }
        return new FournisseurSimule(jdbc, statut);
    }
}
