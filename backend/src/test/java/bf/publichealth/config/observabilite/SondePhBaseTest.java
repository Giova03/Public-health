package bf.publichealth.config.observabilite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Tests unitaires de la sonde phBase — SELECT 1 + latence mesurée en
 * détail ; DOWN quand la connexion est impossible (base coupée : la
 * simulation zonky étant impossible à arrêter, la défaillance est
 * SIMULÉE par un DataSource qui échoue — preuve du catch).
 */
class SondePhBaseTest {

    @Test
    @DisplayName("SELECT 1 passe : UP, latence mesurée en détail (ms) et produit")
    void saineUpAvecLatence() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connexion = mock(Connection.class);
        PreparedStatement ordre = mock(PreparedStatement.class);
        ResultSet resultat = mock(ResultSet.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connexion);
        when(connexion.prepareStatement("SELECT 1")).thenReturn(ordre);
        when(ordre.executeQuery()).thenReturn(resultat);
        when(resultat.next()).thenReturn(true);
        when(resultat.getInt(1)).thenReturn(1);
        when(connexion.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductVersion()).thenReturn("PostgreSQL 16.2");

        Health sante = new SondePhBase(dataSource).health();

        assertThat(sante.getStatus()).isEqualTo(Status.UP);
        Object latence = sante.getDetails().get("latenceMs");
        assertThat(latence).isInstanceOf(Double.class);
        assertThat((Double) latence).isGreaterThanOrEqualTo(0.0);
        assertThat(sante.getDetails()).containsEntry("produit", "PostgreSQL 16.2")
                .containsKey("controle");
    }

    @Test
    @DisplayName("SELECT 1 ne renvoie pas 1 : DOWN")
    void reponseInattendueDown() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connexion = mock(Connection.class);
        PreparedStatement ordre = mock(PreparedStatement.class);
        ResultSet resultat = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connexion);
        when(connexion.prepareStatement("SELECT 1")).thenReturn(ordre);
        when(ordre.executeQuery()).thenReturn(resultat);
        when(resultat.next()).thenReturn(true);
        when(resultat.getInt(1)).thenReturn(0);

        Health sante = new SondePhBase(dataSource).health();
        assertThat(sante.getStatus()).isEqualTo(Status.DOWN);
        assertThat(sante.getDetails()).containsKey("controle");
    }

    @Test
    @DisplayName("connexion impossible (base coupée simulée) : DOWN — c'est le signal LB")
    void connexionImpossibleDown() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection())
                .thenThrow(new SQLException("connexion refusée (simulateur d'arrêt)"));

        Health sante = new SondePhBase(dataSource).health();
        assertThat(sante.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(sante.getDetails().get("error")))
                .contains("connexion refusée");
    }
}
