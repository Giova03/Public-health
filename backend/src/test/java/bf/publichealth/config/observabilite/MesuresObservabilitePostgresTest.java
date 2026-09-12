package bf.publichealth.config.observabilite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de la lecture PostgreSQL — robustesse E8 : la source de
 * mesure ne lève JAMAIS (connexion impossible → snapshot tout indisponible ;
 * erreurs SQL par requête → champs null + anomalies, les autres mesures
 * survivent). L'arrêt de la base zonky étant impossible, la défaillance
 * est SIMULÉE par un JDBC en échec (DataSource/Connection mockés) —
 * preuve des catch, le SQL réel étant couvert par ObservabiliteIT.
 */
class MesuresObservabilitePostgresTest {

    @Test
    @DisplayName("connexion SQL impossible : snapshot TOUT indisponible, aucune exception")
    void connexionImpossibleResteSilencieuse() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection())
                .thenThrow(new SQLException("connexion refusée (simulateur d'arrêt)"));

        SnapshotMesures mesures = new MesuresObservabilitePostgres(dataSource).mesurer();

        assertThat(mesures.syncOutboxEnAttente()).isNull();
        assertThat(mesures.syncOutboxRetardSecondes()).isNull();
        assertThat(mesures.syncOpsParResultat()).isEmpty();
        assertThat(mesures.paiementsParStatut()).isEmpty();
        assertThat(mesures.identityFileRevue()).isNull();
        assertThat(mesures.reconciliationDerniereAgeSecondes()).isNull();
        assertThat(mesures.anomalies()).hasSize(1);
        assertThat(mesures.anomalies().get(0)).contains("connexion SQL impossible");
    }

    @Test
    @DisplayName("table absente (SQLState 42P01) : anomalie « migration non appliquée », jamais d'exception")
    void tableAbsenteDegrade() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connexion = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connexion);
        when(connexion.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatement ordre = mock(PreparedStatement.class);
            if (sql.contains("set_config")) {
                // La GUC de supervision passe normalement.
                ResultSet acquittement = mock(ResultSet.class);
                when(acquittement.next()).thenReturn(true);
                when(ordre.executeQuery()).thenReturn(acquittement);
            } else {
                // Toutes les mesures échouent en « undefined table » (42P01) :
                // le futur schéma absent ne doit PAS faire hurler l'app.
                when(ordre.executeQuery()).thenThrow(new SQLException(
                        "ERROR: relation \"sync.outbox\" does not exist",
                        MesuresObservabilitePostgres.SQLSTATE_TABLE_ABSENTE));
            }
            return ordre;
        });

        SnapshotMesures mesures = new MesuresObservabilitePostgres(dataSource).mesurer();

        assertThat(mesures.syncOutboxEnAttente()).isNull();
        assertThat(mesures.anomalies()).isNotEmpty();
        assertThat(mesures.anomalies().stream()
                .anyMatch(a -> a.contains("42P01") && a.contains("migration")))
                .as("l'anomalie signale la table absente : %s", mesures.anomalies())
                .isTrue();
    }

    @Test
    @DisplayName("classification SQLState : 42P01 = table absente, autre = erreur SQL")
    void classificationSqlState() {
        assertThat(MesuresObservabilitePostgres.tableAbsente(
                new SQLException("relation inconnue", "42P01"))).isTrue();
        assertThat(MesuresObservabilitePostgres.tableAbsente(
                new SQLException("timeout", "57014"))).isFalse();
        assertThat(MesuresObservabilitePostgres.tableAbsente(
                new SQLException("sans SQLState"))).isFalse();
    }

    @Test
    @DisplayName("snapshot VIDE : carte exploitable sans exception")
    void snapshotVideCohérent() {
        SnapshotMesures vide = SnapshotMesures.VIDE;
        assertThat(vide.paiementsParStatut()).isEmpty();
        assertThat(vide.syncOpsParResultat()).isEmpty();
        assertThat(vide.anomalies()).isEmpty();
        assertThat(vide.syncOutboxEnAttente()).isNull();
        assertThat(SnapshotMesures.STATUTS_PAIEMENT).hasSize(8)
                .contains("INITIATED", "RECONCILED");
        assertThat(SnapshotMesures.RESULTATS_SYNC_OP).hasSize(3)
                .containsExactly("APPLIED", "REJECTED", "CONFLICT");
    }
}
