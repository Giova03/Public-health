package bf.publichealth.modules.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import bf.publichealth.common.UuidV7;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — épique E4 : facturation, réconciliation nocturne,
 * orphelins.
 *
 * <p>Couverture : création 201 + Location + idempotence clientRequestId,
 * émission, annulation terminale (re-annulation 409, motif obligatoire
 * 400), gardes SQL V9 (voided/paid terminaux, identité immuable, lignes
 * append-only, transitions légales autorisées), réconciliation manuelle
 * (PENDING→SUCCEEDED via fournisseur simulé + audit PAYMENT_ADVANCED),
 * rétrogradation SUCCEEDED→PENDING IGNORÉE (écart journalisé,
 * historique INTACT), orphelin résolu (webhook orphelin → paiement
 * retrouvé après coup), rapport de run aux compteurs EXACTS,
 * rapprochement des paiements (2 paiements → partially_paid puis solde
 * → paid).</p>
 *
 * <p>Le job nocturne est désactivé (paiements.reconciliation.active=false)
 * : les runs sont déclenchés MANUELLEMENT via POST /api/v1/reconciliation/run.
 * PostgreSQL : Testcontainers quand Docker est présent (CI), PostgreSQL
 * embarqué zonky sinon (poste de développement sans Docker).</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "fedapay.webhook-secret=secret-de-test",
        "paiements.reconciliation.active=false"
})
@AutoConfigureMockMvc
class FacturationReconciliationIT {

    private static final String SECRET = "secret-de-test";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private static String signature(String charge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + java.util.HexFormat.of()
                    .formatHex(mac.doFinal(charge.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC indisponible", e);
        }
    }

    /** Crée une facture (201 attendu) et rend son identifiant. */
    private String creerFacture(UUID patientId, UUID clientRequestId, String itemsJson)
            throws Exception {
        String corps = """
                {"patientId":"%s","clientRequestId":"%s","items":[%s]}
                """.formatted(patientId, clientRequestId, itemsJson);
        String reponse = mockMvc.perform(post("/api/v1/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(reponse, "$.id");
    }

    private ResultActions emettreFacture(String id) throws Exception {
        return mockMvc.perform(post("/api/v1/invoices/%s/issue".formatted(id)));
    }

    private ResultActions annulerFacture(String id, String raison) throws Exception {
        String corps = raison == null ? "{}"
                : "{\"reason\":\"%s\"}".formatted(raison);
        return mockMvc.perform(post("/api/v1/invoices/%s/void".formatted(id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corps));
    }

    /** Crée un paiement (201 attendu) et rend son identifiant. */
    private String creerPaiement(String cle, UUID invoiceId, long montant, String reference)
            throws Exception {
        String corps = "{\"invoiceId\":\"%s\",\"amount\":%d,\"providerRef\":\"%s\"}"
                .formatted(invoiceId, montant, reference);
        String reponse = mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", cle)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corps))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(reponse, "$.id");
    }

    private ResultActions webhook(String eventId, String reference, String statut)
            throws Exception {
        String corps = "{\"reference\":\"%s\",\"status\":\"%s\"}".formatted(reference, statut);
        return mockMvc.perform(post("/api/v1/webhooks/fedapay")
                .header("X-Event-Id", eventId)
                .header("X-FedaPay-Signature", signature(corps))
                .contentType(MediaType.APPLICATION_JSON)
                .content(corps));
    }

    /** Sème l'état distant du fournisseur simulé pour une référence. */
    private void semer(String reference, String statut, String montant) {
        jdbc.update("""
                INSERT INTO payments.provider_simulation (reference, status, amount)
                VALUES (?, ?, ?)
                ON CONFLICT (reference) DO UPDATE SET status = EXCLUDED.status,
                                                      amount = EXCLUDED.amount
                """, reference, statut, montant == null ? null : new java.math.BigDecimal(montant));
    }

    /** Déclenche un run MANUEL et rend le corps du rapport. */
    private String lancerRun() throws Exception {
        return mockMvc.perform(post("/api/v1/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.triggeredBy").value("manuel"))
                .andReturn().getResponse().getContentAsString();
    }

    private long compterTransitions(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payments.payment_transition WHERE payment_id = ?",
                Long.class, paymentId);
    }

    private long compterAudit(String action, UUID entiteId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit.entry WHERE action = ? AND entity_id = ?",
                Long.class, action, entiteId);
    }

    // ------------------------------------------------------------------
    // Facturation : cycle de vie, idempotence, annulation terminale
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Facture : création 201 + Location, rejeu idempotent 200, émission, " +
            "annulation terminale puis re-annulation 409, motif obligatoire 400, 404")
    void cycleDeVieFacture() throws Exception {
        UUID patient = UuidV7.next();
        UUID cleClient = UuidV7.next();
        String items = """
                {"label":"Consultation générale","quantity":1,"unitPrice":2500},
                {"label":"Pansement","quantity":2,"unitPrice":1250.50}
                """;

        String reponse = mockMvc.perform(post("/api/v1/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","clientRequestId":"%s","items":[%s]}
                                """.formatted(patient, cleClient, items)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers
                        .startsWith("/api/v1/invoices/")))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.currency").value("XOF"))
                // Le total est calculé SERVEUR : 2500 + 2 × 1250.50 = 5001.00
                .andExpect(jsonPath("$.total").value(5001.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].amount").value(2500.00))
                .andExpect(jsonPath("$.items[1].amount").value(2501.00))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(reponse, "$.id");

        // Rejeu réseau : 200, la MÊME facture — même clé, mêmes lignes recalculées.
        mockMvc.perform(post("/api/v1/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","clientRequestId":"%s","items":[%s]}
                                """.formatted(patient, cleClient, items)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.total").value(5001.00));

        // Émission : draft → issued, horodatée.
        emettreFacture(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("issued"))
                .andExpect(jsonPath("$.issuedAt").exists());

        // Émettre deux fois : 409, l'historique est intact.
        emettreFacture(id).andExpect(status().isConflict());

        // Annulation : motif OBLIGATOIRE.
        annulerFacture(id, null).andExpect(status().isBadRequest());
        annulerFacture(id, "  ").andExpect(status().isBadRequest());

        // Annulation légale : issued → voided (TERMINAL).
        annulerFacture(id, "Erreur de saisie du destinataire")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"))
                .andExpect(jsonPath("$.voidedReason").value("Erreur de saisie du destinataire"))
                .andExpect(jsonPath("$.voidedAt").exists());

        // Re-annulation : 409 — voided est terminal.
        annulerFacture(id, "une seconde fois").andExpect(status().isConflict());

        // Vue complète + facture inconnue → 404.
        mockMvc.perform(get("/api/v1/invoices/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));
        mockMvc.perform(get("/api/v1/invoices/" + UuidV7.next()))
                .andExpect(status().isNotFound());

        // Liste par patient : la facture y figure ; paramètre manquant → 400.
        mockMvc.perform(get("/api/v1/invoices").param("patientId", patient.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("voided"));
        mockMvc.perform(get("/api/v1/invoices"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Gardes SQL V9 : voided/paid terminaux, identité immuable, lignes append-only
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V9 : voided terminal (UPDATE direct impossible), identité immuable, " +
            "suppression interdite, lignes append-only, transitions légales autorisées")
    void gardeSqlFacture() throws Exception {
        UUID patient = UuidV7.next();
        String id = creerFacture(patient, UuidV7.next(),
                "{\"label\":\"Consultation\",\"quantity\":1,\"unitPrice\":3000}");
        UUID uuid = UUID.fromString(id);

        // Transitions LÉGALES par SQL direct : la garde n'est pas un verrou total.
        jdbc.update("UPDATE payments.invoice SET status='issued', issued_at=now() WHERE id=?",
                uuid);
        jdbc.update("UPDATE payments.invoice SET status='partially_paid' WHERE id=?", uuid);
        jdbc.update("UPDATE payments.invoice SET status='paid' WHERE id=?", uuid);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM payments.invoice WHERE id=?", String.class, uuid))
                .isEqualTo("paid");

        // paid est terminal : plus rien ne bouge, même par SQL direct.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET status='issued' WHERE id=?", uuid))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET status='voided', voided_reason='x', voided_at=now() WHERE id=?",
                uuid))
                .isInstanceOf(DataAccessException.class);

        // Une autre facture : voided est terminal aussi.
        String id2 = creerFacture(patient, UuidV7.next(),
                "{\"label\":\"Pansement\",\"quantity\":1,\"unitPrice\":1500}");
        UUID uuid2 = UUID.fromString(id2);
        jdbc.update("""
                UPDATE payments.invoice SET status='voided', voided_reason='annulée',
                        voided_at=now() WHERE id=?
                """, uuid2);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET status='issued' WHERE id=?", uuid2))
                .isInstanceOf(DataAccessException.class);

        // L'identité ne se réécrit jamais (ni total, ni patient).
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET total=999 WHERE id=?", uuid2))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET patient_id=? WHERE id=?", UuidV7.next(), uuid2))
                .isInstanceOf(DataAccessException.class);

        // Aucune suppression : l'historique comptable est la preuve.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payments.invoice WHERE id=?", uuid2))
                .isInstanceOf(DataAccessException.class);

        // Les lignes sont append-only : ni UPDATE ni DELETE.
        UUID ligneId = jdbc.queryForObject(
                "SELECT id FROM payments.invoice_item WHERE invoice_id=? ORDER BY id LIMIT 1",
                UUID.class, uuid2);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice_item SET label='falsifié' WHERE id=?", ligneId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM payments.invoice_item WHERE id=?", ligneId))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // Réconciliation : avancée PENDING→SUCCEEDED par le fournisseur simulé
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Run manuel : PENDING→SUCCEEDED via fournisseur simulé, " +
            "chemin légal en 2 pas, audit PAYMENT_ADVANCED")
    void reconciliationAvanceVersSucces() throws Exception {
        String reference = "FED-E4-AVANCE-" + UUID.randomUUID();
        String paiementId = creerPaiement("cle-e4-avance-" + UUID.randomUUID(),
                UuidV7.next(), 2500, reference);
        UUID paiement = UUID.fromString(paiementId);

        // Le webhook amène le paiement à PENDING (le chemin existant fonctionne).
        webhook("evt-e4-avance-1", reference, "pending")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(compterTransitions(paiement)).isEqualTo(1);

        // Le fournisseur simulé dit SUCCEEDED : le run fait avancer.
        semer(reference, "SUCCEEDED", "2500");
        String rapport = lancerRun();
        assertThat((Integer) JsonPath.read(rapport, "$.counts.advanced")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) JsonPath.read(rapport, "$.counts.examined")).isGreaterThanOrEqualTo(1);

        // L'état a avancé par le CHEMIN LÉGAL : PENDING→AUTHORIZED→SUCCEEDED (2 pas).
        mockMvc.perform(get("/api/v1/payments/" + paiementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        assertThat(compterTransitions(paiement)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments.payment_transition
                WHERE payment_id = ? AND reason LIKE 'reconciliation:%'
                """, Long.class, paiement)).isEqualTo(2);

        // Audit : l'avancée est tracée.
        assertThat(compterAudit("PAYMENT_ADVANCED", paiement)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Réconciliation : rétrogradation ignorée (forward-only JAMAIS violé)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rétrogradation SUCCEEDED→PENDING : IGNORÉE, écart journalisé, " +
            "historique INTACT, audit RECONCILIATION_DENIED")
    void retrogradationIgnoree() throws Exception {
        String reference = "FED-E4-RETRO-" + UUID.randomUUID();
        String paiementId = creerPaiement("cle-e4-retro-" + UUID.randomUUID(),
                UuidV7.next(), 2500, reference);
        UUID paiement = UUID.fromString(paiementId);

        // Chemin nominal complet : INITIATED→PENDING→AUTHORIZED→SUCCEEDED.
        webhook("evt-e4-retro-1", reference, "pending").andExpect(status().isOk());
        webhook("evt-e4-retro-2", reference, "authorized").andExpect(status().isOk());
        webhook("evt-e4-retro-3", reference, "succeeded").andExpect(status().isOk());
        assertThat(compterTransitions(paiement)).isEqualTo(3);

        // Le prestataire se contredit (retard de vue) : PENDING alors que SUCCEEDED.
        semer(reference, "PENDING", "2500");
        String rapport = lancerRun();
        assertThat((Integer) JsonPath.read(rapport, "$.counts.ignored")).isGreaterThanOrEqualTo(1);

        // Le fait accompli reste : état inchangé, historique INTACT.
        mockMvc.perform(get("/api/v1/payments/" + paiementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        assertThat(compterTransitions(paiement)).isEqualTo(3);

        // L'écart est journalisé durablement (reconciliation_discrepancy).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments.reconciliation_discrepancy
                WHERE kind = 'RETROGRADATION_IGNOREE' AND payment_id = ? AND resolved = false
                """, Long.class, paiement)).isEqualTo(1);

        // Le refus vaut de l'or : trace DENIED.
        assertThat(compterAudit("RECONCILIATION_DENIED", paiement)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Orphelins : webhook reçu sans paiement, résolu quand le paiement arrive
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Orphelin résolu : webhook orphelin → paiement créé après coup → " +
            "run résout l'écart et avance le paiement, audit ORPHAN_RESOLVED")
    void orphelinResolu() throws Exception {
        String reference = "FED-E4-ORPHELIN-" + UUID.randomUUID();

        // 1. Webhook arrivé AVANT le paiement : orphelin consigné, acquitté.
        webhook("evt-e4-orphelin-" + UUID.randomUUID(), reference, "succeeded")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORPHAN"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments.reconciliation_discrepancy
                WHERE kind = 'WEBHOOK_ORPHELIN' AND reference = ? AND resolved = false
                """, Long.class, reference)).isEqualTo(1);

        // 2. Le paiement apparaît ENFIN (initiation après le webhook).
        String paiementId = creerPaiement("cle-e4-orphelin-" + UUID.randomUUID(),
                UuidV7.next(), 2500, reference);
        UUID paiement = UUID.fromString(paiementId);
        semer(reference, "SUCCEEDED", "2500");

        // 3. Le run retrouve le paiement, l'avance et résout l'orphelin.
        String rapport = lancerRun();
        assertThat((Integer) JsonPath.read(rapport, "$.counts.orphans_resolved"))
                .isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/payments/" + paiementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"));
        assertThat(compterTransitions(paiement)).isEqualTo(3); // INITIATED→PENDING→AUTHORIZED→SUCCEEDED

        assertThat(jdbc.queryForObject("""
                SELECT resolved FROM payments.reconciliation_discrepancy
                WHERE kind = 'WEBHOOK_ORPHELIN' AND reference = ?
                """, Boolean.class, reference)).isTrue();
        assertThat(compterAudit("ORPHAN_RESOLVED", paiement)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Écart de montant : le statut fait foi, la divergence se trace
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Montant divergent : état confirmé mais écart MONTANT_DIVERGE journalisé")
    void montantDivergentJournalise() throws Exception {
        String reference = "FED-E4-MONTANT-" + UUID.randomUUID();
        String paiementId = creerPaiement("cle-e4-montant-" + UUID.randomUUID(),
                UuidV7.next(), 2500, reference);
        UUID paiement = UUID.fromString(paiementId);

        // Encaissé chez nous, confirmé chez lui — mais pour un autre montant.
        jdbc.update("UPDATE payments.payment SET state='SUCCEEDED' WHERE id=?", paiement);
        semer(reference, "SUCCEEDED", "999");

        String rapport = lancerRun();
        assertThat((Integer) JsonPath.read(rapport, "$.counts.confirmed"))
                .isGreaterThanOrEqualTo(1);
        assertThat((Integer) JsonPath.read(rapport, "$.detail.ecartsMontant"))
                .isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments.reconciliation_discrepancy
                WHERE kind = 'MONTANT_DIVERGE' AND payment_id = ?
                """, Long.class, paiement)).isEqualTo(1);
        // L'état ne bouge pas : la divergence est tracée, pas décrétée.
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payments.payment WHERE id=?", String.class, paiement))
                .isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // Rapport de run : compteurs EXACTS (situation contrôlée de bout en bout)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rapport de run : compteurs exacts (examined/confirmed/advanced/failed/" +
            "ignored/orphans_resolved), run persisté, historique GET /runs")
    void rapportDeRunCompteursExacts() throws Exception {
        // Terrain contrôlé : plus RIEN d'examinable ni de résolvable avant le run.
        jdbc.update("UPDATE payments.payment SET state='RECONCILED' WHERE state IN " +
                "('INITIATED','PENDING','AUTHORIZED','SUCCEEDED')");
        jdbc.update("UPDATE payments.reconciliation_discrepancy SET resolved=true " +
                "WHERE resolved=false");
        jdbc.update("DELETE FROM payments.provider_simulation");

        // 5 paiements contrôlés :
        String rIgnore = "FED-E6-C1-" + UUID.randomUUID();   // SUCCEEDED + PENDING → IGNORER
        String rConfirm = "FED-E6-C2-" + UUID.randomUUID();  // PENDING + PENDING → CONFIRMER
        String rAvance = "FED-E6-C3-" + UUID.randomUUID();   // INITIATED + SUCCEEDED → AVANCER
        String rEchec = "FED-E6-C4-" + UUID.randomUUID();    // INITIATED + FAILED → ÉCHOUER
        String rInconnu = "FED-E6-C5-" + UUID.randomUUID();  // AUTHORIZED + rien → EXAMINER
        UUID p1 = UUID.fromString(creerPaiement("cle-e6-c1", UuidV7.next(), 1000, rIgnore));
        UUID p2 = UUID.fromString(creerPaiement("cle-e6-c2", UuidV7.next(), 1000, rConfirm));
        UUID p3 = UUID.fromString(creerPaiement("cle-e6-c3", UuidV7.next(), 1000, rAvance));
        UUID p4 = UUID.fromString(creerPaiement("cle-e6-c4", UuidV7.next(), 1000, rEchec));
        UUID p5 = UUID.fromString(creerPaiement("cle-e6-c5", UuidV7.next(), 1000, rInconnu));
        jdbc.update("UPDATE payments.payment SET state='SUCCEEDED' WHERE id=?", p1);
        jdbc.update("UPDATE payments.payment SET state='PENDING' WHERE id=?", p2);
        jdbc.update("UPDATE payments.payment SET state='AUTHORIZED' WHERE id=?", p5);
        semer(rIgnore, "PENDING", "1000");
        semer(rConfirm, "PENDING", "1000");
        semer(rAvance, "SUCCEEDED", "1000");
        semer(rEchec, "FAILED", "1000");

        String rapport = mockMvc.perform(post("/api/v1/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggeredBy").value("manuel"))
                .andExpect(jsonPath("$.finishedAt").exists())
                // Compteurs EXACTS : 5 examinés = 1 confirmé + 1 avancé + 1 échoué
                // + 1 ignoré + 1 état inconnu (pas de catégorie, détail only).
                .andExpect(jsonPath("$.counts.examined").value(5))
                .andExpect(jsonPath("$.counts.confirmed").value(1))
                .andExpect(jsonPath("$.counts.advanced").value(1))
                .andExpect(jsonPath("$.counts.failed").value(1))
                .andExpect(jsonPath("$.counts.ignored").value(1))
                .andExpect(jsonPath("$.counts.orphans_resolved").value(0))
                .andExpect(jsonPath("$.detail.etatsInconnus").value(1))
                .andExpect(jsonPath("$.detail.facturesExaminees").value(0))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(rapport, "$.id");

        // Le run est PERSISTÉ : compteurs jsonb relus tels quels.
        String countsTexte = jdbc.queryForObject(
                "SELECT counts::text FROM payments.reconciliation_run WHERE id=?",
                String.class, UUID.fromString(runId));
        assertThat((Integer) JsonPath.read(countsTexte, "$.examined")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(countsTexte, "$.ignored")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT triggered_by FROM payments.reconciliation_run WHERE id=?",
                String.class, UUID.fromString(runId))).isEqualTo("manuel");
        assertThat(jdbc.queryForObject(
                "SELECT finished_at IS NOT NULL FROM payments.reconciliation_run WHERE id=?",
                Boolean.class, UUID.fromString(runId))).isTrue();

        // Les verdicts se voient dans la base.
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payments.payment WHERE id=?", String.class, p3))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payments.payment WHERE id=?", String.class, p4))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM payments.payment WHERE id=?", String.class, p1))
                .isEqualTo("SUCCEEDED");

        // Historique des runs : le plus récent d'abord, limite appliquée.
        mockMvc.perform(get("/api/v1/reconciliation/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(runId));
        mockMvc.perform(get("/api/v1/reconciliation/runs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/reconciliation/runs").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Rapprochement : 2 paiements → partially_paid, solde → paid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rapprochement : paiement partiel → partially_paid, solde → paid, " +
            "paiements liés à la facture (invoice_id)")
    void rapprochementPartielPuisSolde() throws Exception {
        UUID patient = UuidV7.next();
        String factureId = creerFacture(patient, UuidV7.next(),
                "{\"label\":\"Hospitalisation\",\"quantity\":2,\"unitPrice\":2500}");
        UUID facture = UUID.fromString(factureId);
        emettreFacture(factureId).andExpect(status().isOk());

        // Deux paiements rattachés à la facture À L'INITIATION (contrat V2).
        String referenceA = "FED-E4-RAPP-A-" + UUID.randomUUID();
        String referenceB = "FED-E4-RAPP-B-" + UUID.randomUUID();
        UUID paiementA = UUID.fromString(creerPaiement("cle-e4-rapp-a", facture, 3000, referenceA));
        UUID paiementB = UUID.fromString(creerPaiement("cle-e4-rapp-b", facture, 2000, referenceB));

        // Premier run : A encaissé (3000/5000), B encore PENDING.
        semer(referenceA, "SUCCEEDED", "3000");
        semer(referenceB, "PENDING", "2000");
        lancerRun();

        mockMvc.perform(get("/api/v1/invoices/" + factureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partially_paid"))
                .andExpect(jsonPath("$.cumulEncaisse").value(3000.00));

        // Second run : le solde arrive (2000), la facture est soldée.
        semer(referenceB, "SUCCEEDED", "2000");
        lancerRun();

        mockMvc.perform(get("/api/v1/invoices/" + factureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.cumulEncaisse").value(5000.00));

        // Les deux paiements restent liés à la facture (le lien fait foi).
        assertThat(jdbc.queryForList(
                "SELECT id FROM payments.payment WHERE invoice_id=?",
                UUID.class, facture)).containsExactlyInAnyOrder(paiementA, paiementB);
        // Soldée : la facture ne bouge plus, même pas par SQL direct.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments.invoice SET status='issued' WHERE id=?", facture))
                .isInstanceOf(DataAccessException.class);
    }
}
