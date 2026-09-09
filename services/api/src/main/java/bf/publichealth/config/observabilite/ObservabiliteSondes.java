package bf.publichealth.config.observabilite;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sondes de santé approfondies — épique E8.
 *
 * <p>Deux {@link org.springframework.boot.actuate.health.HealthIndicator}
 * nommés ({@code phOutbox}, {@code phBase}) contribuent à
 * {@code /actuator/health} — le nom du bean DÉTERMINE le nom du composant
 * dans la réponse (d'où {@code @Bean(name = ...)}).</p>
 *
 * <ul>
 *   <li>{@code phOutbox} : UP si volume &lt; 1000 ET retard &lt; 3600 s,
 *       DOWN sinon, UNKNOWN si la mesure est impossible (table absente) —
 *       la sortie de l'outbox ne doit pas traîner ;</li>
 *   <li>{@code phBase} : SELECT 1 + latence mesurée (ms) sur une connexion
 *       dédiée ; DOWN si la base est injoignable.</li>
 * </ul>
 *
 * <p>Aucune autre configuration ne change : les sondes par défaut de Boot
 * ({@code db}, {@code ping}…) restent en place.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ObservabiliteSondes {

    @Bean(name = "phOutbox")
    public SondePhOutbox phOutbox(SourceMesures sourceMesures) {
        return new SondePhOutbox(sourceMesures);
    }

    @Bean(name = "phBase")
    public SondePhBase phBase(DataSource dataSource) {
        return new SondePhBase(dataSource);
    }
}
