package bf.publichealth.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * PREUVE RLS — épique E5 (rôle applicatif app_rw, JDBC pur).
 *
 * <p>La migration V10 impose ENABLE + FORCE ROW LEVEL SECURITY sur toutes
 * les tables attribuables. Ce test connecte le rôle applicatif
 * app_rw (non propriétaire, NOLOGIN, endossé par SET ROLE depuis le
 * super-utilisateur) et montre que :</p>
 * <ul>
 *   <li>avec la GUC app.user_id posée, app_rw ne voit QUE ses lignes —
 *       table par table (les 10 tables attribuables) ;</li>
 *   <li>sans GUC, app_rw ne voit RIEN (fail-closed) ;</li>
 *   <li>le INSERT est contrôlé par WITH CHECK : sa propre ligne passe,
 *       celle d'un autre est rejetée (violation row-level security) ;</li>
 *   <li>le journal d'audit reste inscriptible par l'applicatif
 *       (V5 : audit_insert WITH CHECK true — l'audit ne doit jamais être
 *       bloqué) ;</li>
 *   <li>le rôle admin (GUC app.roles, style V5) contourne la portée
 *       utilisateur — l'examen a posteriori reste possible ;</li>
 *   <li>le super-utilisateur (migrations : Flyway en CI/test) voit TOUT
 *       — PostgreSQL ne soumet JAMAIS un super-utilisateur à la RLS,
 *       y compris FORCE : c'est le contournement propriétaire qui
 *       garantit que la suite existante reste verte sans modification.</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest
class RlsAppRwIT {

    /** Source de base : conteneur en CI, embarqué en local. */
    private static final PostgreSQLContainer<?> POSTGRES = dockerDisponible()
            ? new PostgreSQLContainer<>("postgres:16-alpine") : null;
    private static EmbeddedPostgres EMBARQUE;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        } else {
            try {
                EMBARQUE = EmbeddedPostgres.builder().start();
            } catch (Exception e) {
                throw new IllegalStateException("PostgreSQL embarqué indisponible", e);
            }
        }
    }

    @DynamicPropertySource
    static void sourceDeDonnees(DynamicPropertyRegistry registre) {
        if (POSTGRES != null) {
            registre.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registre.add("spring.datasource.username", POSTGRES::getUsername);
            registre.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registre.add("spring.datasource.url",
                    () -> EMBARQUE.getJdbcUrl("postgres", "postgres"));
            registre.add("spring.datasource.username", () -> "postgres");
            registre.add("spring.datasource.password", () -> "");
        }
    }

    private static boolean dockerDisponible() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Un cas de preuve : amorçage (2 lignes, X et Y) + comptage des lignes de X. */
    private record CasRls(String table, String colonne, String amorcage, String comptageX) {
    }

    // ------------------------------------------------------------------
    // Connexions JDBC pures (super-utilisateur, puis SET ROLE app_rw)
    // ------------------------------------------------------------------

    private static Connection connexionNue() throws SQLException {
        return POSTGRES != null
                ? DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                : DriverManager.getConnection(EMBARQUE.getJdbcUrl("postgres", "postgres"),
                        "postgres", "");
    }

    private static int compter(Connection connexion, String sql) throws SQLException {
        try (Statement ordre = connexion.createStatement();
             ResultSet resultat = ordre.executeQuery(sql)) {
            resultat.next();
            return resultat.getInt(1);
        }
    }

    private static void executer(Connection connexion, String sql) throws SQLException {
        try (Statement ordre = connexion.createStatement()) {
            ordre.execute(sql);
        }
    }

    /** Amorce les 10 tables attribuables : une ligne pour X, une ligne pour Y. */
    private static List<CasRls> cas(UUID x, UUID y) {
        UUID patient1 = UUID.randomUUID();
        UUID patient2 = UUID.randomUUID();
        UUID structure = UUID.randomUUID();
        UUID prescription = UUID.randomUUID();
        UUID ligne = UUID.randomUUID();
        UUID factureX = UUID.randomUUID();
        UUID factureY = UUID.randomUUID();

        String patients = "INSERT INTO identity.patient (id) VALUES ('%s'), ('%s')"
                .formatted(patient1, patient2);

        String support = "INSERT INTO prescription.prescription (id, patient_id, issued_at) "
                + "VALUES ('%s', '%s', now());"
                + "INSERT INTO prescription.prescription_item (id, prescription_id, "
                + "medication_code, medication_label, quantity_prescribed) "
                + "VALUES ('%s', '%s', 'PARA', 'Paracétamol 500mg', 10)";

        return List.of(
                new CasRls("identity.identity_match", "reviewed_by",
                        patients + ";"
                                + "INSERT INTO identity.identity_match (id, candidate_a, candidate_b, "
                                + "score, method, reviewed_by) VALUES ('%s', '%s', '%s', 1.0, 'EXACT', '%s')"
                                .formatted(UUID.randomUUID(), patient1, patient2, x)
                                + ";"
                                + "INSERT INTO identity.identity_match (id, candidate_a, candidate_b, "
                                + "score, method, reviewed_by) VALUES ('%s', '%s', '%s', 1.0, 'EXACT', '%s')"
                                .formatted(UUID.randomUUID(), patient2, patient1, y),
                        "SELECT count(*) FROM identity.identity_match WHERE reviewed_by = '%s'"
                                .formatted(x)),
                new CasRls("identity.merge_log", "performed_by",
                        "INSERT INTO identity.merge_log (id, master_id, merged_id, fields, performed_by, reason) "
                                + "VALUES ('%s', '%s', '%s', '{}'::jsonb, '%s', 'fusion de test X')".formatted(
                                UUID.randomUUID(), patient1, patient2, x)
                                + ";"
                                + "INSERT INTO identity.merge_log (id, master_id, merged_id, fields, performed_by, reason) "
                                + "VALUES ('%s', '%s', '%s', '{}'::jsonb, '%s', 'fusion de test Y')".formatted(
                                UUID.randomUUID(), patient2, patient1, y),
                        "SELECT count(*) FROM identity.merge_log WHERE performed_by = '%s'"
                                .formatted(x)),
                new CasRls("payments.payment", "initiated_by",
                        "INSERT INTO payments.payment (id, invoice_id, amount, idempotency_key, state, initiated_by) "
                                + "VALUES ('%s', '%s', 1000, 'idem-%s', 'INITIATED', '%s')".formatted(
                                UUID.randomUUID(), factureX, factureX, x)
                                + ";"
                                + "INSERT INTO payments.payment (id, invoice_id, amount, idempotency_key, state, initiated_by) "
                                + "VALUES ('%s', '%s', 1000, 'idem-%s', 'INITIATED', '%s')".formatted(
                                UUID.randomUUID(), factureY, factureY, y),
                        "SELECT count(*) FROM payments.payment WHERE initiated_by = '%s'"
                                .formatted(x)),
                new CasRls("clinical.encounter", "practitioner_id",
                        "INSERT INTO clinical.encounter (id, patient_id, facility_id, practitioner_id, "
                                + "encounter_class, started_at) VALUES ('%s', '%s', '%s', '%s', 'ambulatory', now())"
                                .formatted(UUID.randomUUID(), patient1, structure, x)
                                + ";"
                                + "INSERT INTO clinical.encounter (id, patient_id, facility_id, practitioner_id, "
                                + "encounter_class, started_at) VALUES ('%s', '%s', '%s', '%s', 'ambulatory', now())"
                                .formatted(UUID.randomUUID(), patient2, structure, y),
                        "SELECT count(*) FROM clinical.encounter WHERE practitioner_id = '%s'"
                                .formatted(x)),
                new CasRls("prescription.prescription", "prescriber_id",
                        "INSERT INTO prescription.prescription (id, patient_id, prescriber_id, issued_at) "
                                + "VALUES ('%s', '%s', '%s', now())".formatted(
                                UUID.randomUUID(), patient1, x)
                                + ";"
                                + "INSERT INTO prescription.prescription (id, patient_id, prescriber_id, issued_at) "
                                + "VALUES ('%s', '%s', '%s', now())".formatted(
                                UUID.randomUUID(), patient2, y),
                        "SELECT count(*) FROM prescription.prescription WHERE prescriber_id = '%s'"
                                .formatted(x)),
                new CasRls("prescription.dispensation", "dispensed_by",
                        support.formatted(prescription, patient1, ligne, prescription) + ";"
                                + "INSERT INTO prescription.dispensation (id, prescription_id, item_id, "
                                + "quantity, dispensed_by) VALUES ('%s', '%s', '%s', 5, '%s')".formatted(
                                UUID.randomUUID(), prescription, ligne, x)
                                + ";"
                                + "INSERT INTO prescription.dispensation (id, prescription_id, item_id, "
                                + "quantity, dispensed_by) VALUES ('%s', '%s', '%s', 5, '%s')".formatted(
                                UUID.randomUUID(), prescription, ligne, y),
                        "SELECT count(*) FROM prescription.dispensation WHERE dispensed_by = '%s'"
                                .formatted(x)),
                new CasRls("sync.op", "user_id",
                        "INSERT INTO sync.op (op_id, user_id, entity, entity_id, result) "
                                + "VALUES ('%s', '%s', 'patient', '%s', 'APPLIED')".formatted(
                                UUID.randomUUID(), x, patient1)
                                + ";"
                                + "INSERT INTO sync.op (op_id, user_id, entity, entity_id, result) "
                                + "VALUES ('%s', '%s', 'patient', '%s', 'APPLIED')".formatted(
                                UUID.randomUUID(), y, patient2),
                        "SELECT count(*) FROM sync.op WHERE user_id = '%s'".formatted(x)),
                new CasRls("sync.device", "user_id",
                        "INSERT INTO sync.device (id, user_id, device_name) "
                                + "VALUES ('%s', '%s', 'tablette-X-%s')".formatted(
                                UUID.randomUUID(), x, x)
                                + ";"
                                + "INSERT INTO sync.device (id, user_id, device_name) "
                                + "VALUES ('%s', '%s', 'tablette-Y-%s')".formatted(
                                UUID.randomUUID(), y, y),
                        "SELECT count(*) FROM sync.device WHERE user_id = '%s'".formatted(x)),
                new CasRls("audit.entry", "actor_id",
                        "INSERT INTO audit.entry (occurred_at, actor_id, action, entity, result, hash) "
                                + "VALUES (now(), '%s', 'PATIENT_READ', 'patient', 'SUCCESS', 'h-%s')".formatted(x, x)
                                + ";"
                                + "INSERT INTO audit.entry (occurred_at, actor_id, action, entity, result, hash) "
                                + "VALUES (now(), '%s', 'PATIENT_READ', 'patient', 'SUCCESS', 'h-%s')".formatted(y, y),
                        "SELECT count(*) FROM audit.entry WHERE actor_id = '%s'".formatted(x)),
                new CasRls("audit.emergency_access", "user_id",
                        "INSERT INTO audit.emergency_access (id, user_id, patient_id, reason, expires_at) "
                                + "VALUES ('%s', '%s', '%s', 'urgence de test X', now() + interval '30 minutes')"
                                .formatted(UUID.randomUUID(), x, patient1)
                                + ";"
                                + "INSERT INTO audit.emergency_access (id, user_id, patient_id, reason, expires_at) "
                                + "VALUES ('%s', '%s', '%s', 'urgence de test Y', now() + interval '30 minutes')"
                                .formatted(UUID.randomUUID(), y, patient2),
                        "SELECT count(*) FROM audit.emergency_access WHERE user_id = '%s'"
                                .formatted(x)));
    }

    // ------------------------------------------------------------------
    // Preuves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("app_rw + app.user_id : ne voit QUE ses lignes, sur les 10 tables attribuables")
    void appRwNeVoitQueSesLignes() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        List<CasRls> cas = cas(x, y);

        // Amorçage en super-utilisateur (contournement RLS — rôle migration).
        try (Connection superUtilisateur = connexionNue()) {
            for (CasRls unCas : cas) {
                executer(superUtilisateur, unCas.amorcage());
            }
        }

        // En tant que X : exactement SA ligne, sur CHAQUE table.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = '" + x + "'");
            for (CasRls unCas : cas) {
                assertThat(compter(connexion, unCas.comptageX()))
                        .as("%s : app_rw (app.user_id=X) doit voir sa ligne uniquement", unCas.table())
                        .isEqualTo(1);
            }
        }

        // En tant que Y : la ligne de X est INVISIBLE.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = '" + y + "'");
            for (CasRls unCas : cas) {
                assertThat(compter(connexion, unCas.comptageX()))
                        .as("%s : app_rw (app.user_id=Y) ne doit PAS voir la ligne de X", unCas.table())
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("app_rw sans app.user_id : fail-closed, aucune ligne")
    void appRwSansIdentiteNeVoitRien() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        try (Connection superUtilisateur = connexionNue()) {
            executer(superUtilisateur, cas(x, y).get(2).amorcage()); // payments.payment
        }
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = ''");
            int visibles = compter(connexion, "SELECT count(*) FROM payments.payment");
            assertThat(visibles).as("sans identité, la RLS est fail-closed").isZero();
        }
    }

    @Test
    @DisplayName("INSERT contrôlé : sa ligne passe, celle d'autrui est rejetée (WITH CHECK)")
    void insertionControleeParRls() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = '" + x + "'");

            // Sa propre ligne : acceptée.
            // (parenthèses obligatoires : sinon .formatted() ne s'appliquerait
            // qu'au DERNIER fragment concaténé et des %s litéraux partiraient
            // en base — bug observé : « invalid input syntax for type uuid: %s »)
            executer(connexion, ("INSERT INTO payments.payment (id, invoice_id, amount, "
                    + "idempotency_key, state, initiated_by) VALUES ('%s', '%s', 1000, 'idem-%s', "
                    + "'INITIATED', '%s')").formatted(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), x));

            // La ligne d'autrui : violation de la policy row-level security.
            assertThatThrownBy(() -> executer(connexion,
                    ("INSERT INTO payments.payment (id, invoice_id, amount, idempotency_key, state, "
                            + "initiated_by) VALUES ('%s', '%s', 1000, 'idem-%s', 'INITIATED', '%s')")
                            .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), y)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");

            // Le journal d'audit reste inscriptible par l'applicatif (V5 :
            // audit_insert WITH CHECK true — l'audit ne doit jamais être bloqué),
            // MÊME pour une entrée attribuée à un autre acteur.
            executer(connexion, "INSERT INTO audit.entry (occurred_at, actor_id, action, entity, "
                    + "result, hash) VALUES (now(), '" + y + "', 'PATIENT_READ', 'patient', "
                    + "'SUCCESS', 'h-libre')");
            // La ligne inscrite est lisible par SON acteur (policy V10
            // audit_entry_proprietaire_select — portée par propriétaire).
            executer(connexion, "SET app.user_id = '" + y + "'");
            assertThat(compter(connexion,
                    "SELECT count(*) FROM audit.entry WHERE hash = 'h-libre'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("rôle admin (GUC app.roles, style V5) : portée utilisateur contournée")
    void roleAdminContourneLaPorteeUtilisateur() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        try (Connection superUtilisateur = connexionNue()) {
            executer(superUtilisateur, cas(x, y).get(2).amorcage()); // payments.payment
        }
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = ''");
            executer(connexion, "SET app.roles = 'admin'");
            int visibles = compter(connexion,
                    "SELECT count(*) FROM payments.payment WHERE initiated_by IN ('" + x + "', '" + y + "')");
            assertThat(visibles).as("l'admin relit tout (examen a posteriori)").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("super-utilisateur (Flyway/migrations) : contournement total, même FORCE")
    void superUtilisateurVoitTout() throws Exception {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        try (Connection superUtilisateur = connexionNue()) {
            executer(superUtilisateur, cas(x, y).get(2).amorcage()); // payments.payment
            int visibles = compter(superUtilisateur,
                    "SELECT count(*) FROM payments.payment WHERE initiated_by IN ('" + x + "', '" + y + "')");
            assertThat(visibles).as("le super-utilisateur voit tout (tests existants inchangés)")
                    .isEqualTo(2);
        }
    }
}
