package bf.publichealth.config.observabilite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lecture PostgreSQL des mesures d'exploitation — épique E8.
 *
 * <p>Robustesse (exigence E8) : {@link #mesurer()} ne lève JAMAIS — chaque
 * mesure est captée individuellement, une table absente (SQLState 42P01,
 * futur schéma) produit un champ {@code null} + une anomalie plutôt qu'un
 * échec. Les SELECT sont mono-schéma, sans {@code SELECT *} : des agrégats
 * ciblés ({@code count}/{@code min}/{@code max}) sur des tables de taille
 * raisonnable, profitant des index partiels V1/V4/V9
 * ({@code idx_identity_match_pending}, {@code idx_outbox_unpublished}).</p>
 *
 * <p><b>RLS</b> : les tables V10 portent {@code FORCE ROW LEVEL SECURITY}
 * ({@code payments.payment}, {@code sync.op}, {@code identity.identity_match}…).
 * La supervision doit compter en NATIONAL, pas par utilisateur : la GUC
 * {@code app.roles='admin'} est posée sur LA connexion de mesure, puis
 * REMISE À CHAÎNE VIDE dans un {@code finally} (même garantie que
 * {@code ControleRlsDataSource} E5 : aucune fuite de privilège vers le
 * pool). Sur un rôle {@code BYPASSRLS}/{@code superuser} (CI zonky,
 * Testcontainers, service_role Supabase) la GUC est sans effet.</p>
 *
 * <p>Aucune donnée sensible ne sort de la base : compteurs et âges.</p>
 */
@Component
public class MesuresObservabilitePostgres implements SourceMesures {

    private static final Logger LOG = LoggerFactory.getLogger(MesuresObservabilitePostgres.class);

    /** SQLState PostgreSQL « undefined_table » (42P01) — schéma/migration absente. */
    static final String SQLSTATE_TABLE_ABSENTE = "42P01";

    static final String SQL_OUTBOX_EN_ATTENTE =
            "SELECT count(*) FROM sync.outbox WHERE published = false";

    static final String SQL_OUTBOX_RETARD =
            "SELECT coalesce(extract(epoch from (now() - min(occurred_at)))::double precision, 0) "
                    + "FROM sync.outbox WHERE published = false";

    /** Un seul parcours de sync.op pour les trois cumuls (APPLIED/REJECTED/CONFLICT). */
    static final String SQL_OPS =
            "SELECT count(*) FILTER (WHERE result = 'APPLIED'), "
                    + "count(*) FILTER (WHERE result = 'REJECTED'), "
                    + "count(*) FILTER (WHERE result = 'CONFLICT') "
                    + "FROM sync.op";

    static final String SQL_PAIEMENTS_PAR_STATUT =
            "SELECT state, count(*) FROM payments.payment GROUP BY state";

    static final String SQL_IDENTITY_FILE_REVUE =
            "SELECT count(*) FROM identity.identity_match WHERE status = 'PENDING'";

    static final String SQL_RECONCILIATION_AGE =
            "SELECT extract(epoch from (now() - max(finished_at)))::double precision "
                    + "FROM payments.reconciliation_run WHERE finished_at IS NOT NULL";

    private final DataSource dataSource;

    public MesuresObservabilitePostgres(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public SnapshotMesures mesurer() {
        try (Connection connexion = dataSource.getConnection()) {
            // GUC de supervision (vision nationale à travers les policies RLS
            // V5/V10) : posée puis TOUJOURS remise à vide avant restitution.
            poserGucRoles(connexion);
            try {
                return mesurerSur(connexion);
            } finally {
                reinitialiserGucRoles(connexion);
            }
        } catch (SQLException e) {
            // Connexion impossible : snapshot « tout indisponible », l'app vit.
            LOG.warn("Mesures d'exploitation indisponibles — connexion SQL impossible : {}",
                    e.getMessage());
            return new SnapshotMesures(null, null, null, null, null, null,
                    List.of("connexion SQL impossible : " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Mesures individuelles — un échec n'emporte jamais les autres
    // (autocommit : chaque SELECT est sa propre transaction)
    // ------------------------------------------------------------------

    private SnapshotMesures mesurerSur(Connection connexion) {
        List<String> anomalies = new ArrayList<>();
        Long outboxEnAttente = protectedAnomalie(anomalies, "sync.outbox",
                () -> lireLong(connexion, SQL_OUTBOX_EN_ATTENTE));
        Double outboxRetard = protectedAnomalie(anomalies, "sync.outbox",
                () -> lireDecimal(connexion, SQL_OUTBOX_RETARD));
        Map<String, Long> opsParResultat = protectedAnomalie(anomalies, "sync.op",
                () -> lireOps(connexion));
        Map<String, Long> parStatut = protectedAnomalie(anomalies, "payments.payment",
                () -> lirePaiementsParStatut(connexion));
        Long fileRevue = protectedAnomalie(anomalies, "identity.identity_match",
                () -> lireLong(connexion, SQL_IDENTITY_FILE_REVUE));
        Double ageReconciliation = protectedAnomalie(anomalies, "payments.reconciliation_run",
                () -> lireDecimal(connexion, SQL_RECONCILIATION_AGE));

        if (ageReconciliation == null) {
            anomalies.add("aucun payments.reconciliation_run terminé (finished_at) — "
                    + "âge de réconciliation indisponible");
            LOG.info("Aucun run de réconciliation terminé : la réconciliation nocturne "
                    + "n'a encore jamais abouti sur cette base");
        }
        return new SnapshotMesures(outboxEnAttente, outboxRetard, opsParResultat,
                parStatut, fileRevue, ageReconciliation, anomalies);
    }

    /** Exécute une mesure, convertit son échec SQL en champ null + anomalie. */
    private static <T> T protectedAnomalie(List<String> anomalies, String table,
            LectureSql<T> lecture) {
        try {
            return lecture.lire();
        } catch (SQLException e) {
            if (tableAbsente(e)) {
                // Futur schéma / migration non appliquée : la mesure
                // disparaît proprement (gauge NaN, sonde UNKNOWN), sans
                // faire crier l'exploitation pour une table inconnue.
                anomalies.add("table %s absente (SQLState 42P01) — migration non appliquée ?"
                        .formatted(table));
                LOG.info("Mesure indisponible : la table {} est absente (SQLState 42P01)",
                        table);
            } else {
                anomalies.add("erreur SQL sur %s : %s (SQLState %s)"
                        .formatted(table, e.getMessage(), e.getSQLState()));
                LOG.warn("Mesure indisponible sur {} : {} (SQLState {})",
                        table, e.getMessage(), e.getSQLState());
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface LectureSql<T> {
        T lire() throws SQLException;
    }

    /** Table absente : SQLState 42P01 (undefined_table) — cf. mandat E8. */
    static boolean tableAbsente(SQLException e) {
        return SQLSTATE_TABLE_ABSENTE.equals(e.getSQLState());
    }

    // ------------------------------------------------------------------
    // Lectures JDBC ciblées (jamais de SELECT *)
    // ------------------------------------------------------------------

    private static Long lireLong(Connection connexion, String sql) throws SQLException {
        try (PreparedStatement ordre = connexion.prepareStatement(sql);
             ResultSet resultat = ordre.executeQuery()) {
            if (!resultat.next()) {
                throw new SQLException("aucune ligne renvoyée par un agrégat — résultat inattendu");
            }
            return resultat.getLong(1);
        }
    }

    private static Double lireDecimal(Connection connexion, String sql) throws SQLException {
        try (PreparedStatement ordre = connexion.prepareStatement(sql);
             ResultSet resultat = ordre.executeQuery()) {
            if (!resultat.next()) {
                throw new SQLException("aucune ligne renvoyée par un agrégat — résultat inattendu");
            }
            double valeur = resultat.getDouble(1);
            return resultat.wasNull() ? null : valeur;
        }
    }

    /** Les 3 résultats du CHECK V4 sont TOUJOURS présents (0 si aucune
     *  ligne) : les 3 séries restent numériques, jamais NaN « fantôme ». */
    private static Map<String, Long> lireOps(Connection connexion) throws SQLException {
        Map<String, Long> parResultat = new LinkedHashMap<>();
        for (String resultat : SnapshotMesures.RESULTATS_SYNC_OP) {
            parResultat.put(resultat, 0L);
        }
        try (PreparedStatement ordre = connexion.prepareStatement(SQL_OPS);
             ResultSet resultat = ordre.executeQuery()) {
            if (!resultat.next()) {
                throw new SQLException("aucune ligne renvoyée par un agrégat — résultat inattendu");
            }
            parResultat.put("APPLIED", resultat.getLong(1));
            parResultat.put("REJECTED", resultat.getLong(2));
            parResultat.put("CONFLICT", resultat.getLong(3));
        }
        return parResultat;
    }

    private static Map<String, Long> lirePaiementsParStatut(Connection connexion)
            throws SQLException {
        // Les 8 états du CHECK V2 sont TOUJOURS présents (0 si aucune ligne) :
        // les 8 séries restent numériques, jamais NaN « fantôme ».
        Map<String, Long> parStatut = new LinkedHashMap<>();
        for (String statut : SnapshotMesures.STATUTS_PAIEMENT) {
            parStatut.put(statut, 0L);
        }
        try (PreparedStatement ordre = connexion.prepareStatement(SQL_PAIEMENTS_PAR_STATUT);
             ResultSet resultat = ordre.executeQuery()) {
            while (resultat.next()) {
                parStatut.put(resultat.getString(1), resultat.getLong(2));
            }
        }
        return parStatut;
    }

    // ------------------------------------------------------------------
    // GUC de supervision — posée puis remise à vide (aucune fuite au pool)
    // ------------------------------------------------------------------

    private static void poserGucRoles(Connection connexion) throws SQLException {
        executerSetConfig(connexion, "admin");
    }

    private static void reinitialiserGucRoles(Connection connexion) {
        try {
            executerSetConfig(connexion, "");
        } catch (SQLException e) {
            // Même tolérance que ControleRlsDataSource (E5) : la remise à
            // zéro ne doit jamais masquer l'erreur d'origine.
            LOG.warn("Réinitialisation de app.roles impossible après mesure : {}",
                    e.getMessage());
        }
    }

    private static void executerSetConfig(Connection connexion, String roles)
            throws SQLException {
        try (PreparedStatement ordre = connexion.prepareStatement(
                "SELECT set_config(?, ?, false)")) {
            ordre.setString(1, "app.roles");
            ordre.setString(2, roles);
            try (ResultSet resultat = ordre.executeQuery()) {
                if (!resultat.next()) {
                    throw new SQLException("set_config n'a rien renvoyé");
                }
            }
        }
    }
}
