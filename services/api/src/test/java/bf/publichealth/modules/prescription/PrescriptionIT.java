package bf.publichealth.modules.prescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
 * Intégration E2E — épique E3 : prescriptions & dispensation.
 *
 * <p>Couverture : création 201 + Location + idempotence offline
 * (clientRequestId), dispensation partielle CUMULÉE ×2, dépassement
 * → 409 avec le restant exact, annulation puis dispensation → 409,
 * contre-entrée entered-in-error, rejeu idempotent de la dispensation
 * (aucune ligne en double), patient inexistant → 404 (vérification via
 * le module identity), gardes append-only SQL de la migration V8
 * (dispensation immuable, prescription réécrite interdite).</p>
 *
 * <p>PostgreSQL : Testcontainers quand Docker est présent (CI), PostgreSQL
 * embarqué zonky sinon (poste de développement sans Docker).</p>
 */
@Tag("integration")
@SpringBootTest(properties = "fedapay.webhook-secret=secret-de-test")
@AutoConfigureMockMvc
class PrescriptionIT {

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
    // Chargeurs
    // ------------------------------------------------------------------

    private String creerPatient() throws Exception {
        // Patronyme + naissance ALÉATOIRES : le MPI détecte les doublons
        // (E1) — un patronyme ou une naissance partagés entre tests
        // déclencheraient le 409 de la zone grise, pas deux dossiers.
        String alea = UUID.randomUUID().toString().replace("-", "");
        String naissance = "%d-%02d-%02d".formatted(
                1960 + (alea.charAt(12) % 40), 1 + (alea.charAt(13) % 12),
                1 + (alea.charAt(14) % 28));
        var result = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gender":"female","birthDate":"%s",
                                 "names":[{"use":"official","family":"%s","given":"%s"}]}
                                """.formatted(naissance,
                                        alea.substring(0, 8).toUpperCase(),
                                        alea.substring(8, 12).toUpperCase())))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String creerPrescription(String patientId, UUID clientRequestId,
                                     String quantite) throws Exception {
        return creerPrescription(patientId, clientRequestId, quantite, null);
    }

    private String creerPrescription(String patientId, UUID clientRequestId,
                                     String quantite, java.time.Instant issuedAt) throws Exception {
        var result = mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPrescription(patientId, clientRequestId, quantite, issuedAt)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String corpsPrescription(String patientId, UUID clientRequestId,
                                     String quantite, java.time.Instant issuedAt) {
        String champIssuedAt = issuedAt == null ? ""
                : "\"issuedAt\": \"%s\",".formatted(issuedAt);
        String champCle = clientRequestId == null ? "null" : "\"%s\"".formatted(clientRequestId);
        return """
                {
                  "patientId": "%s",
                  %s
                  "clientRequestId": %s,
                  "prescriberId": "%s",
                  "items": [{
                    "medicationCode": "PARA-500",
                    "medicationLabel": "Paracétamol 500 mg",
                    "dose": "1 comprimé",
                    "form": "comprimé",
                    "route": "oral",
                    "frequency": "3 fois par jour",
                    "durationDays": 5,
                    "quantityPrescribed": %s
                  }]
                }
                """.formatted(patientId, champIssuedAt, champCle, UUID.randomUUID(), quantite);
    }

    private ResultActions dispenser(String prescriptionId, String ligneId,
                                    String quantite, UUID clientRequestId) throws Exception {
        String champCle = clientRequestId == null ? "null" : "\"%s\"".formatted(clientRequestId);
        return mockMvc.perform(post("/api/v1/prescriptions/%s/items/%s/dispense"
                        .formatted(prescriptionId, ligneId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity": %s, "clientRequestId": %s, "dispensedBy": "%s"}
                                """.formatted(quantite, champCle, UUID.randomUUID())));
    }

    private String premiereLigne(String prescriptionId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/prescriptions/" + prescriptionId))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.items[0].id");
    }

    // ------------------------------------------------------------------
    // Création + dispensation partielle cumulée
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Création : 201 + Location, statut actif ; dispensation partielle ×2 cumulée")
    void creationEtDispensationsCumulees() throws Exception {
        String patient = creerPatient();
        String prescription = creerPrescription(patient, UUID.randomUUID(), "15.00");
        String ligne = premiereLigne(prescription);

        // Deux retraits fractionnés : 5.00 puis 3.00 sur 15.00 prescrits.
        dispenser(prescription, ligne, "5.00", UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(5.0))
                .andExpect(jsonPath("$.restant").value(10.0));
        dispenser(prescription, ligne, "3.00", UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restant").value(7.0));

        // Le cumul fait foi : dispensé 8.00, restant 7.00, deux lignes de preuve.
        mockMvc.perform(get("/api/v1/prescriptions/" + prescription))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.items[0].quantityPrescribed").value(15.0))
                .andExpect(jsonPath("$.items[0].quantityDispensed").value(8.0))
                .andExpect(jsonPath("$.items[0].restant").value(7.0))
                .andExpect(jsonPath("$.dispensations", hasSize(2)));

        UUID uuid = UUID.fromString(prescription);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM prescription.dispensation WHERE prescription_id = ?",
                Long.class, uuid)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT sum(quantity) FROM prescription.dispensation WHERE prescription_id = ?",
                java.math.BigDecimal.class, uuid))
                .isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("Dépassement : 409 avec {restant, demande} exacts, aucune ligne ajoutée, audit DENIED")
    void depassementRefuseAvecRestantExact() throws Exception {
        String patient = creerPatient();
        // 10.00 prescrits, 4.00 déjà délivrés → restant exact 6.00.
        String prescription = creerPrescription(patient, UUID.randomUUID(), "10.00");
        String ligne = premiereLigne(prescription);
        dispenser(prescription, ligne, "4.00", UUID.randomUUID())
                .andExpect(status().isCreated());

        dispenser(prescription, ligne, "7.00", UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Quantité non dispensable"))
                .andExpect(jsonPath("$.restant").value(6.0))
                .andExpect(jsonPath("$.demande").value(7.0));

        // Refus = rien n'écrit : toujours UNE dispensation.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM prescription.dispensation WHERE item_id = ?
                """, Long.class, UUID.fromString(ligne))).isEqualTo(1);

        // Le refus vaut de l'or : trace DENIED (survit au rollback).
        Integer refus = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'DISPENSATION_DENIED' AND result = 'DENIED'
                  AND details->>'prescriptionId' = ?
                """, Integer.class, prescription);
        assertThat(refus).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Dispensation exacte au restant : acceptée, restant 0 ensuite")
    void dispensationExacteAuRestant() throws Exception {
        String patient = creerPatient();
        String prescription = creerPrescription(patient, UUID.randomUUID(), "12.00");
        String ligne = premiereLigne(prescription);

        dispenser(prescription, ligne, "7.00", UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restant").value(5.0));
        dispenser(prescription, ligne, "5.00", UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.restant").value(0.0));

        // Épuisé : la moindre unité supplémentaire est refusée.
        dispenser(prescription, ligne, "1.00", UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.restant").value(0.0));
    }

    // ------------------------------------------------------------------
    // Annulation & contre-entrée — append-only par design
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Annulation : motif obligatoire, dispensation ensuite refusée (409), re-annulation 409")
    void annulationPuisDispensationRefusee() throws Exception {
        String patient = creerPatient();
        String prescription = creerPrescription(patient, UUID.randomUUID(), "9.00");
        String ligne = premiereLigne(prescription);

        // Motif manquant : la contre-entrée ne se fait jamais en silence.
        mockMvc.perform(post("/api/v1/prescriptions/%s/cancel".formatted(prescription))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/prescriptions/%s/cancel".formatted(prescription))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sortie du patient interrompue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelReason").value("Sortie du patient interrompue"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        // Un fait historique ne dispense plus.
        dispenser(prescription, ligne, "1.00", UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Prescription inactive"))
                .andExpect(jsonPath("$.statut").value("cancelled"));

        // Ré-annuler une prescription annulée : 409, rien ne bouge.
        mockMvc.perform(post("/api/v1/prescriptions/%s/cancel".formatted(prescription))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"encore\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transition de prescription illégale"))
                .andExpect(jsonPath("$.from").value("cancelled"));

        // L'annulation est auditée avec son motif.
        Integer traces = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PRESCRIPTION_CANCELLED' AND entity_id = ? AND reason IS NOT NULL
                """, Integer.class, UUID.fromString(prescription));
        assertThat(traces).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Contre-entrée entered-in-error : 200, dispensation refusée, jamais de DELETE")
    void contreEntreeEnErreur() throws Exception {
        String patient = creerPatient();
        String prescription = creerPrescription(patient, UUID.randomUUID(), "6.00");
        String ligne = premiereLigne(prescription);

        mockMvc.perform(post("/api/v1/prescriptions/%s/entered-in-error"
                        .formatted(prescription))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest()); // motif manquant

        mockMvc.perform(post("/api/v1/prescriptions/%s/entered-in-error"
                        .formatted(prescription))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Mauvais patient, erreur de saisie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("entered-in-error"))
                .andExpect(jsonPath("$.cancelReason").value("Mauvais patient, erreur de saisie"));

        dispenser(prescription, ligne, "1.00", UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statut").value("entered-in-error"));

        // La ligne existe TOUJOURS : l'historique clinique est la preuve.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM prescription.prescription WHERE id = ?",
                Long.class, UUID.fromString(prescription))).isEqualTo(1);
        Integer traces = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PRESCRIPTION_ENTERED_IN_ERROR' AND entity_id = ?
                """, Integer.class, UUID.fromString(prescription));
        assertThat(traces).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Idempotence offline — rejeu = 200, jamais deux lignes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Rejeu idempotent : création ET dispensation, mêmes client_request_id → 200, zéro doublon")
    void rejeuIdempotentCreationEtDispensation() throws Exception {
        String patient = creerPatient();
        UUID cleCreation = UUID.randomUUID();
        String corps = corpsPrescription(patient, cleCreation, "20.00", null);

        var premier = mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isCreated())
                .andReturn();
        String prescription = JsonPath.read(premier.getResponse().getContentAsString(), "$.id");
        String ligne = JsonPath.read(premier.getResponse().getContentAsString(), "$.items[0].id");

        // Rejeu réseau de la création : 200, la MÊME prescription.
        mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(prescription));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM prescription.prescription WHERE client_request_id = ?",
                Long.class, cleCreation)).isEqualTo(1);

        UUID cleDispensation = UUID.randomUUID();
        var premiere = dispenser(prescription, ligne, "8.00", cleDispensation)
                .andExpect(status().isCreated())
                .andReturn();
        String dispensation = JsonPath.read(
                premiere.getResponse().getContentAsString(), "$.id");

        // Rejeu réseau de la dispensation : 200, la MÊME ligne, pas de cumul fantôme.
        dispenser(prescription, ligne, "8.00", cleDispensation)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dispensation))
                .andExpect(jsonPath("$.quantity").value(8.0))
                .andExpect(jsonPath("$.restant").value(12.0));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM prescription.dispensation WHERE client_request_id = ?",
                Long.class, cleDispensation)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT sum(quantity) FROM prescription.dispensation WHERE item_id = ?",
                java.math.BigDecimal.class, UUID.fromString(ligne)))
                .isEqualByComparingTo("8.00");
    }

    // ------------------------------------------------------------------
    // Vérification du patient via le module identity (loi n°3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Patient inexistant : 404 ProblemDetail, aucune prescription écrite")
    void patientInexistantRefuse() throws Exception {
        UUID fantome = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPrescription(fantome.toString(), null, "5.00", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Patient introuvable"))
                .andExpect(jsonPath("$.patientId").value(fantome.toString()));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM prescription.prescription WHERE patient_id = ?",
                Long.class, fantome)).isEqualTo(0);

        // Le refus est tracé DENIED (l'échec vaut de l'or).
        Integer refus = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PRESCRIPTION_REFUSED' AND result = 'DENIED'
                  AND details->>'patientId' = ?
                """, Integer.class, fantome.toString());
        assertThat(refus).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Listes et introuvables
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Dossier pharmacologique par patient : la plus récente d'abord ; patientId requis")
    void listeParPatient() throws Exception {
        String patient = creerPatient();
        // issuedAt explicites : l'ordre (issued_at DESC) est déterministe.
        String ancienne = creerPrescription(patient, UUID.randomUUID(), "5.00",
                java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        String recente = creerPrescription(patient, UUID.randomUUID(), "6.00");

        mockMvc.perform(get("/api/v1/prescriptions").param("patientId", patient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(recente))
                .andExpect(jsonPath("$[1].id").value(ancienne))
                .andExpect(jsonPath("$[0].status").value("active"));

        // Sans patientId : la requête est incomplete, pas silencieuse.
        mockMvc.perform(get("/api/v1/prescriptions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requête incomplète"));
    }

    @Test
    @DisplayName("404 propres : prescription inconnue, ligne étrangère à la prescription")
    void introuvables() throws Exception {
        String patient = creerPatient();
        String prescription = creerPrescription(patient, UUID.randomUUID(), "5.00");
        String autre = creerPrescription(creerPatient(), UUID.randomUUID(), "5.00");
        String ligneEtrangere = premiereLigne(autre);

        mockMvc.perform(get("/api/v1/prescriptions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Prescription introuvable"));

        mockMvc.perform(post("/api/v1/prescriptions/%s/cancel".formatted(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"motif\"}"))
                .andExpect(status().isNotFound());

        // Une ligne d'AUTRE prescription est introuvable ici : jamais de
        // dispensation croisée entre dossiers.
        dispenser(prescription, ligneEtrangere, "1.00", UUID.randomUUID())
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/prescriptions/%s/items/%s/dispense"
                        .formatted(UUID.randomUUID(), UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Validation : prescription sans ligne et quantité non positive refusées (400)")
    void validationDesEntrees() throws Exception {
        String patient = creerPatient();
        mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","items":[]}
                                """.formatted(patient)))
                .andExpect(status().isBadRequest());

        String prescription = creerPrescription(patient, UUID.randomUUID(), "5.00");
        String ligne = premiereLigne(prescription);
        mockMvc.perform(post("/api/v1/prescriptions/%s/items/%s/dispense"
                        .formatted(prescription, ligne))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/prescriptions/%s/items/%s/dispense"
                        .formatted(prescription, ligne))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": -3.00}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Gardes SQL (migration V8) — le schéma porte la loi architecturale
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V8 : dispensation immuable (UPDATE quantity interdit, DELETE interdit)")
    void gardeAppendOnlyDispensation() {
        UUID prescription = UuidV7.next();
        UUID ligne = UuidV7.next();
        UUID dispensation = UuidV7.next();
        jdbc.update("""
                INSERT INTO prescription.prescription (id, patient_id, status, issued_at)
                VALUES (?, ?, 'active', now())""",
                prescription, UuidV7.next());
        jdbc.update("""
                INSERT INTO prescription.prescription_item
                    (id, prescription_id, medication_code, medication_label, quantity_prescribed)
                VALUES (?, ?, 'PARA-500', 'Paracétamol 500 mg', 15.00)""",
                ligne, prescription);
        jdbc.update("""
                INSERT INTO prescription.dispensation (id, prescription_id, item_id, quantity)
                VALUES (?, ?, ?, 5.00)""",
                dispensation, prescription, ligne);

        // Le fait accompli ne se réécrit pas, quel que soit le champ.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.dispensation SET quantity = 6.00 WHERE id = ?", dispensation))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.dispensation SET dispensed_by = ? WHERE id = ?",
                UuidV7.next(), dispensation))
                .isInstanceOf(DataAccessException.class);

        // Ni ne se supprime : le cumul des dispensations est la preuve.
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM prescription.dispensation WHERE id = ?", dispensation))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("V8 : prescription append-only — réécriture interdite, seule contre-entrée légale")
    void gardeAppendOnlyPrescription() {
        UUID prescription = UuidV7.next();
        jdbc.update("""
                INSERT INTO prescription.prescription (id, patient_id, status, issued_at)
                VALUES (?, ?, 'active', now())""",
                prescription, UuidV7.next());

        // Réécrire une valeur clinique : interdit, même depuis 'active'.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.prescription SET issued_at = now() WHERE id = ?",
                prescription))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prescription.prescription SET status = 'active', cancel_reason = 'x'
                WHERE id = ?""", prescription))
                .isInstanceOf(DataAccessException.class);

        // La seule mutation légale : la contre-entrée active → cancelled.
        jdbc.update("""
                UPDATE prescription.prescription
                SET status = 'cancelled', cancel_reason = 'test V8', cancelled_at = now()
                WHERE id = ?""", prescription);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM prescription.prescription WHERE id = ?",
                String.class, prescription)).isEqualTo("cancelled");

        // Annulée, plus rien ne bouge : ni retour arrière, ni suppression.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.prescription SET status = 'active' WHERE id = ?",
                prescription))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.prescription SET status = 'entered-in-error' WHERE id = ?",
                prescription))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM prescription.prescription WHERE id = ?", prescription))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("V8 : une ligne de prescription ne se réécrit ni ne se supprime")
    void gardeAppendOnlyLigne() {
        UUID prescription = UuidV7.next();
        UUID ligne = UuidV7.next();
        jdbc.update("""
                INSERT INTO prescription.prescription (id, patient_id, status, issued_at)
                VALUES (?, ?, 'active', now())""",
                prescription, UuidV7.next());
        jdbc.update("""
                INSERT INTO prescription.prescription_item
                    (id, prescription_id, medication_code, medication_label, quantity_prescribed)
                VALUES (?, ?, 'AMOX-250', 'Amoxicilline 250 mg', 21.00)""",
                ligne, prescription);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prescription.prescription_item SET medication_label = 'X' WHERE id = ?",
                ligne))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM prescription.prescription_item WHERE id = ?", ligne))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // Audit : chaque écriture est tracée, chaîne intacte
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Audit : PRESCRIPTION_CREATED et DISPENSATION_RECORDED chaînées")
    void auditDesEvenementsPrescription() throws Exception {
        String patient = creerPatient();
        UUID cleCreation = UUID.randomUUID();
        String prescription = creerPrescription(patient, cleCreation, "4.00");
        String ligne = premiereLigne(prescription);
        dispenser(prescription, ligne, "2.00", UUID.randomUUID())
                .andExpect(status().isCreated());

        Integer creations = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PRESCRIPTION_CREATED' AND entity = 'prescription'
                  AND entity_id = ?
                """, Integer.class, UUID.fromString(prescription));
        Integer dispensations = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'DISPENSATION_RECORDED' AND result = 'SUCCESS'
                  AND details->>'prescriptionId' = ?
                """, Integer.class, prescription);
        assertThat(creations).isGreaterThanOrEqualTo(1);
        assertThat(dispensations).isGreaterThanOrEqualTo(1);

        // Le chaînage global reste intact (chaque entrée scelle la précédente).
        var entrees = jdbc.queryForList(
                "SELECT prev_hash, hash FROM audit.entry ORDER BY id");
        assertThat(entrees).isNotEmpty();
        for (int i = 1; i < entrees.size(); i++) {
            assertThat(entrees.get(i).get("prev_hash"))
                    .as("maillon %d", i)
                    .isEqualTo(entrees.get(i - 1).get("hash"));
        }
    }
}
