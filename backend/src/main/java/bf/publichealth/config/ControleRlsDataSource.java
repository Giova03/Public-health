package bf.publichealth.config;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Décorateur de DataSource (épique E5) : pose les GUC PostgreSQL
 * {@code app.user_id} et {@code app.roles} sur CHAQUE connexion empruntée
 * pendant une requête authentifiée par JWT — c'est le pont entre les
 * claims {@code sub}/{@code app_role} et les policies RLS de V5/V10
 * ({@code current_setting('app.user_id', true)::uuid}).
 *
 * <p>Garantie d'isolation : les GUC sont posées à l'emprunt
 * ({@code SELECT set_config('app.user_id', ?, false)}) puis REMISES À
 * CHAÎNE VIDE au retour au pool (interception de {@code close()}). C'est
 * l'équivalent robuste d'un {@code SET LOCAL} : aucune fuite d'identité
 * entre requêtes, et cela fonctionne aussi pour les lectures hors
 * transaction (autocommit) contrairement au {@code SET LOCAL} strict.
 * Le seul contexte posé est un UUID et une liste de rôles — jamais de
 * token.</p>
 *
 * <p>Posé par {@link ControleRlsDataSourcePostProcessor} UNIQUEMENT quand
 * {@code securite.jwt.actif=true} : sinon la DataSource reste nue et le
 * comportement est strictement inchangé (posture Sprint 0).</p>
 */
public class ControleRlsDataSource extends DelegatingDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(ControleRlsDataSource.class);

    public ControleRlsDataSource(DataSource source) {
        super(source);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connexion = super.getConnection();
        ContexteRls contexte = ContexteRls.courant();
        if (contexte == null) {
            // Hors requête authentifiée : connexion nue, rien à poser.
            return connexion;
        }
        poserGuc(connexion, "app.user_id", contexte.utilisateur().toString());
        // Rôles de la requête (CSV minuscules) : alimente app.has_role() des
        // policies V5/V10 (contournement admin). Vide = aucun rôle.
        poserGuc(connexion, "app.roles", contexte.rolesCsv());
        return connexionProxy(connexion);
    }

    @Override
    public Connection getConnection(String identifiant, String motDePasse) throws SQLException {
        // Connexions dédiées (outils) : pas de contexte de requête à poser.
        return super.getConnection(identifiant, motDePasse);
    }

    private static void poserGuc(Connection connexion, String guc, String valeur)
            throws SQLException {
        try (PreparedStatement ordre = connexion.prepareStatement(
                "SELECT set_config(?, ?, false)")) {
            ordre.setString(1, guc);
            ordre.setString(2, valeur);
            ordre.executeQuery();
        }
    }

    private static void reinitialiserGuc(Connection connexion, String guc) {
        try (PreparedStatement ordre = connexion.prepareStatement(
                "SELECT set_config(?, '', false)")) {
            ordre.setString(1, guc);
            ordre.executeQuery();
        } catch (SQLException e) {
            // La remise à zéro ne doit JAMAIS casser le retour au pool.
            LOG.warn("Réinitialisation de {} impossible au retour de connexion : {}",
                    guc, e.getMessage());
        }
    }

    /** Proxy JDK : intercepte close() pour réinitialiser les GUC avant le retour au pool. */
    private static Connection connexionProxy(Connection delegate) {
        InvocationHandler gestionnaire = (proxy, methode, arguments) -> {
            if ("close".equals(methode.getName())) {
                reinitialiserGuc(delegate, "app.user_id");
                reinitialiserGuc(delegate, "app.roles");
                return null;
            }
            if ("equals".equals(methode.getName())) {
                return proxy == arguments[0] || delegate.equals(arguments[0]);
            }
            if ("hashCode".equals(methode.getName())) {
                return delegate.hashCode();
            }
            if ("toString".equals(methode.getName())) {
                return "ConnexionRLS(" + delegate + ")";
            }
            return methode.invoke(delegate, arguments);
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, gestionnaire);
    }
}
