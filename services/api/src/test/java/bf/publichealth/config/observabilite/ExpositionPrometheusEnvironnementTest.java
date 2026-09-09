package bf.publichealth.config.observabilite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Tests unitaires de l'exposition programmatique de /actuator/prometheus —
 * le mécanisme de bootstrap E8 qui enrichit la liste d'exposition SANS
 * toucher à application.yml (le yml y expose health,info,metrics).
 */
class ExpositionPrometheusEnvironnementTest {

    private static StandardEnvironment environnementAvecYml(Map<String, Object> yml) {
        StandardEnvironment environnement = new StandardEnvironment();
        environnement.getPropertySources().addFirst(
                new MapPropertySource("applicationConfigYml", yml));
        return environnement;
    }

    @Test
    @DisplayName("yml « health,info,metrics » : prometheus AJOUTÉ, la volonté yml conservée")
    void enrichitLaListeDuYml() {
        StandardEnvironment environnement = environnementAvecYml(Map.of(
                "management.endpoints.web.exposure.include", "health,info,metrics"));
        new ExpositionPrometheusEnvironnement()
                .postProcessEnvironment(environnement, new SpringApplication());

        assertThat(environnement.getProperty(
                "management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,metrics,prometheus");
        // Composants de santé visibles par défaut (code), pour l'exploitation.
        assertThat(environnement.getProperty(
                "management.endpoint.health.show-components")).isEqualTo("always");
    }

    @Test
    @DisplayName("aucune liste définie : défaut Boot (health,info) complété de prometheus")
    void sansListeLeDefautNEstPasEcrase() {
        StandardEnvironment environnement = new StandardEnvironment();
        new ExpositionPrometheusEnvironnement()
                .postProcessEnvironment(environnement, new SpringApplication());

        // Jamais « prometheus seul » : la sonde /actuator/health reste exposée.
        assertThat(environnement.getProperty(
                "management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
    }

    @Test
    @DisplayName("choix explicite de l'intégrateur : show-components non écrasé")
    void respecteLaVolonteIntegrateur() {
        StandardEnvironment environnement = environnementAvecYml(Map.of(
                "management.endpoint.health.show-components", "when-authorized"));
        new ExpositionPrometheusEnvironnement()
                .postProcessEnvironment(environnement, new SpringApplication());

        assertThat(environnement.getProperty(
                "management.endpoint.health.show-components")).isEqualTo("when-authorized");
    }

    @Test
    @DisplayName("observabilite.prometheus.actif=false : aucune propriété posée")
    void desactivationParIntegrateur() {
        StandardEnvironment environnement = environnementAvecYml(Map.of(
                "observabilite.prometheus.actif", "false",
                "management.endpoints.web.exposure.include", "health,info,metrics"));
        new ExpositionPrometheusEnvironnement()
                .postProcessEnvironment(environnement, new SpringApplication());

        assertThat(environnement.getProperty(
                "management.endpoints.web.exposure.include")).isEqualTo("health,info,metrics");
        assertThat(environnement.getProperty(
                "management.endpoint.health.show-components")).isNull();
    }

    @Test
    @DisplayName("idempotent : un second passage ne duplique rien")
    void idempotent() {
        StandardEnvironment environnement = environnementAvecYml(Map.of(
                "management.endpoints.web.exposure.include", "health,info"));
        ExpositionPrometheusEnvironnement processeur = new ExpositionPrometheusEnvironnement();
        processeur.postProcessEnvironment(environnement, new SpringApplication());
        processeur.postProcessEnvironment(environnement, new SpringApplication());

        assertThat(environnement.getProperty(
                "management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus");
    }
}
