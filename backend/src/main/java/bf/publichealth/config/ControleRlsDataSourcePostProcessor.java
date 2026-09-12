package bf.publichealth.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Pose le {@link ControleRlsDataSource} sur la DataSource de
 * l'application, UNIQUEMENT quand {@code securite.jwt.actif=true}.
 *
 * <p>Avec le flag à false (défaut, posture Sprint 0), la DataSource
 * n'est PAS décorée : comportement observable strictement inchangé.
 * Le décorateur s'applique aussi à Flyway — en dehors des requêtes, le
 * ThreadLocal est vide, la connexion passe nue, aucune GUC posée.</p>
 */
@Component
public class ControleRlsDataSourcePostProcessor implements BeanPostProcessor, Ordered {

    private final boolean jwtActif;

    public ControleRlsDataSourcePostProcessor(Environment environnement) {
        // Lu tôt : le BeanPostProcessor s'instancie avant les beans ordinaires.
        this.jwtActif = Boolean.TRUE.equals(
                environnement.getProperty("securite.jwt.actif", Boolean.class, false));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String nomBean) {
        if (jwtActif && bean instanceof DataSource source
                && !(bean instanceof ControleRlsDataSource)) {
            return new ControleRlsDataSource(source);
        }
        return bean;
    }

    /** Après la configuration/binding de la DataSource, avant tout usage. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
