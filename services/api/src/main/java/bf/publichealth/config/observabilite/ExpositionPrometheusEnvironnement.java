package bf.publichealth.config.observabilite;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Exposition de {@code /actuator/prometheus} PAR LE CODE — épique E8.
 *
 * <p><b>Pourquoi un EnvironmentPostProcessor</b> :
 * {@code application.yml} est INTROUVABLE en modification (contrainte
 * d'intégration — il expose {@code health,info,metrics} sans
 * {@code prometheus}). Ce processeur tourne au bootstrap, APRÈS le
 * chargement du yml (ordre {@code HIGHEST_PRECEDENCE + 100},
 * ConfigData = {@code HIGHEST_PRECEDENCE + 10}) : il lit la liste
 * d'exposition EFFECTIVE (yml + variables d'environnement + ligne de
 * commande), lui AJOUTE {@code prometheus}, et repose la propriété
 * enrichie dans une source prioritaire.</p>
 *
 * <p><b>Échappatoires intégrateur</b> (aucun yml requis) :</p>
 * <ul>
 *   <li>retirer le scraping : {@code observabilite.prometheus.actif=false}
 *       (propriété d'environnement ou ligne de commande) ;</li>
 *   <li>l'exclure sans coupler le reste :
 *       {@code management.endpoints.web.exposure.exclude=prometheus}
 *       (l'exclusion l'emporte sur l'inclusion dans Actuator) ;</li>
 *   <li>tout reprendre en main : définir
 *       {@code management.endpoints.web.exposure.include} — la valeur est
 *       lue puis enrichie, la volonté de l'intégrateur est conservée.</li>
 * </ul>
 *
 * <p>Il pose aussi {@code management.endpoint.health.show-components=always}
 * (uniquement si l'intégrateur ne l'a pas définie) : la santé agrégée
 * publique montre les composants {@code phOutbox}/{@code phBase} et leur
 * statut — les DÉTAILS (seuils, latence) restent masqués
 * ({@code show-details} reste à sa valeur Boot {@code never} par défaut ;
 * l'équipe SRE l'active en exploitation, cf. docs/observabilite.md).</p>
 */
public class ExpositionPrometheusEnvironnement implements EnvironmentPostProcessor, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ExpositionPrometheusEnvironnement.class);

    static final String PROPRIETE_INCLUDE = "management.endpoints.web.exposure.include";
    static final String PROPRIETE_ACTIF = "observabilite.prometheus.actif";
    static final String PROPRIETE_COMPOSANTS_SANTE = "management.endpoint.health.show-components";

    static final String ENDPOINT_PROMETHEUS = "prometheus";
    static final String NOM_SOURCE = "observabiliteE8Prometheus";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environnement,
            SpringApplication application) {
        if (!Boolean.TRUE.equals(
                environnement.getProperty(PROPRIETE_ACTIF, Boolean.class, true))) {
            LOG.info("Scraping Prometheus désactivé (observabilite.prometheus.actif=false)");
            return;
        }
        MutablePropertySources sources = environnement.getPropertySources();
        if (sources.contains(NOM_SOURCE)) {
            return; // idempotent (rechargements de contexte)
        }

        Map<String, Object> proprietes = new HashMap<>();

        // Inclusion courante (yml/env/ligne de commande), enrichie de prometheus.
        // Si AUCUNE liste n'est définie, on repart du défaut Boot (health)
        // complété de info — jamais de « prometheus seul » qui masquerait
        // la sonde /actuator/health.
        Set<String> inclusion = new LinkedHashSet<>();
        String courante = environnement.getProperty(PROPRIETE_INCLUDE);
        if (courante != null && !courante.isBlank()) {
            for (String element : courante.split(",")) {
                if (!element.isBlank()) {
                    inclusion.add(element.trim());
                }
            }
        } else {
            inclusion.add("health");
            inclusion.add("info");
        }
        boolean ajoute = inclusion.add(ENDPOINT_PROMETHEUS);
        proprietes.put(PROPRIETE_INCLUDE, String.join(",", inclusion));

        // Composants de santé visibles par défaut (code), sauf choix explicite.
        if (environnement.getProperty(PROPRIETE_COMPOSANTS_SANTE) == null) {
            proprietes.put(PROPRIETE_COMPOSANTS_SANTE, "always");
        }

        sources.addFirst(new MapPropertySource(NOM_SOURCE, proprietes));
        if (ajoute) {
            LOG.info("Actuator : {} ajouté à l'exposition web ({} = {})",
                    ENDPOINT_PROMETHEUS, PROPRIETE_INCLUDE, String.join(",", inclusion));
        }
    }

    /**
     * Après ConfigDataEnvironmentPostProcessor (HIGHEST+10, chargement du
     * yml) : la propriété enrichie repose sur la valeur Effective du yml.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
