package bf.publichealth.config.observabilite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Sonde {@code phBase} — épique E8 : la base répond-elle, et en combien
 * de temps ?
 *
 * <p>Un {@code SELECT 1} sur UNE connexion dédiée à la sonde (empruntée à
 * la demande, une seule à la fois, jamais partagée avec le trafic
 * applicatif) ; la latence est mesurée et exposée en détail
 * ({@code latenceMs}), avec le produit/serveur. La connexion échoue →
 * DOWN : c'est LE cas légitime où la santé globale bascule (le
 * répartiteur de charge sort l'instance).</p>
 *
 * <p>Aucune requête sur une table applicative : cette sonde ne connaît
 * ni les migrations ni les schémas — l'indisponibilité d'une table est
 * du ressort de {@code phOutbox} (UNKNOWN), pas de celle-ci.</p>
 */
public class SondePhBase implements HealthIndicator {

    private final DataSource dataSource;

    public SondePhBase(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        long debut = System.nanoTime();
        try (Connection connexion = dataSource.getConnection()) {
            boolean un;
            try (PreparedStatement ordre = connexion.prepareStatement("SELECT 1");
                 ResultSet resultat = ordre.executeQuery()) {
                un = resultat.next() && resultat.getInt(1) == 1;
            }
            double latenceMs = (System.nanoTime() - debut) / 1_000_000.0;
            if (!un) {
                return Health.down()
                        .withDetail("controle", "SELECT 1 n'a pas renvoyé 1")
                        .build();
            }
            return Health.up()
                    .withDetail("controle", "SELECT 1 sur une connexion dédiée à la sonde")
                    .withDetail("latenceMs", latenceMs)
                    .withDetail("produit", version(connexion))
                    .build();
        } catch (SQLException e) {
            // Base injoignable / timeout / credentials : DOWN assumé —
            // c'est le signal que l'instance ne peut pas servir.
            return Health.down(e)
                    .withDetail("controle", "SELECT 1 sur une connexion dédiée à la sonde")
                    .build();
        }
    }

    private static String version(Connection connexion) {
        try {
            String version = connexion.getMetaData().getDatabaseProductVersion();
            return version == null || version.isBlank() ? "inconnue" : version;
        } catch (SQLException e) {
            return "inconnue";
        }
    }
}
