package bf.publichealth.modules.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import bf.publichealth.common.UuidV7;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — épique E2 (côté API) : protocole de synchronisation
 * offline.
 *
 * <p>Couverture : lot offline complet (patient + encounter + observation,
 * tout APPLIED, données visibles), rejeu du même lot (mêmes résultats,
 * aucune donnée dupliquée), doublon patient = CONFLICT avec candidats
 * sans casser le lot, entité inconnue = REJECTED, observation orpheline
 * = REJECTED (FK), déclaration d'appareil idempotente, delta miroir
 * avec curseur keyset.</p>
 *
 * <p>PostgreSQL : Testcontainers en CI (Docker), embarqué zonky en local.
 * Les UUID v7 « côté client » sont produits par {@link UuidV7} — même
 * algorithme que le PWA (opId, entityId, clientRequestId).</p>
 */
@Tag("integration")
@SpringBootTest(properties = "fedapay.webhook-secret=secret-de-test")
@AutoConfigureMockMvc
class SyncIT {

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

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Chargeurs (l'opId, l'entityId et le clientRequestId sont des UUID
    // v7 « générés côté client », comme le fera le PWA)
    // ------------------------------------------------------------------

    private static UUID v7() {
        return UuidV7.next();
    }

    private String declarerAppareil(UUID userId, String nom) throws Exception {
        var resultat = mockMvc.perform(post("/api/v1/sync/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"deviceName\":\"%s\"}"
                                .formatted(userId, nom)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(resultat.getResponse().getContentAsString(), "$.deviceId");
    }

    private String opPatient(UUID opId, UUID clientRequestId, UUID createdBy,
                             String family, String given, String birthDate,
                             String telephone, boolean forceCreate) {
        return """
                {"opId":"%s","entity":"patient","payload":{
                  "clientRequestId":"%s","forceCreate":%b,"gender":"female","birthDate":"%s",
                  "names":[{"use":"official","family":"%s","given":"%s"}],
                  "telecoms":[{"system":"phone","value":"%s","use":"mobile"}],
                  "createdBy":"%s"}}
                """.formatted(opId, clientRequestId, forceCreate, birthDate,
                family, given, telephone, createdBy);
    }

    private String opEncounter(UUID opId, UUID encounterId, UUID patientId) {
        return """
                {"opId":"%s","entity":"encounter","entityId":"%s","payload":{
                  "patientId":"%s","facilityId":"%s","encounterClass":"consultation",
                  "reason":"Consultation generale - fievre",
                  "startedAt":"2026-02-10T08:30:00Z"}}
                """.formatted(opId, encounterId, patientId, UUID.randomUUID());
    }

    private String opObservation(UUID opId, UUID observationId, UUID encounterId, UUID patientId) {
        return """
                {"opId":"%s","entity":"observation","entityId":"%s","payload":{
                  "encounterId":"%s","patientId":"%s","code":"TEMP_ER","valueNum":38.9,
                  "effectiveAt":"2026-02-10T08:45:00Z"}}
                """.formatted(opId, observationId, encounterId, patientId);
    }

    private String opCondition(UUID opId, UUID conditionId, UUID encounterId, UUID patientId) {
        return """
                {"opId":"%s","entity":"condition","entityId":"%s","payload":{
                  "encounterId":"%s","patientId":"%s","code":"MALARIA_SUSPECT",
                  "clinicalStatus":"active"}}
                """.formatted(opId, conditionId, encounterId, patientId);
    }

    private String lot(UUID deviceId, String... ops) {
        String contenu = String.join(",", ops);
        return deviceId == null
                ? "{\"ops\":[" + contenu + "]}"
                : "{\"deviceId\":\"" + deviceId + "\",\"ops\":[" + contenu + "]}";
    }

    private String uplink(String corps) throws Exception {
        return mockMvc.perform(post("/api/v1/sync")
                        .contentType(MediaType.APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Création d'un patient via l'API normale (référence de conflit / delta). */
    private String creerPatient(String family, String given, String birthDate,
                                String telephone) throws Exception {
        var resultat = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gender":"female","birthDate":"%s",
                                 "names":[{"use":"official","family":"%s","given":"%s"}],
                                 "telecoms":[{"system":"phone","value":"%s","use":"mobile"}]}
                                """.formatted(birthDate, family, given, telephone)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(resultat.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------
    // Déclaration d'appareil
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Appareil : double déclaration (même user + deviceName) = même deviceId")
    void declarationAppareilIdempotente() throws Exception {
        UUID user = UUID.randomUUID();
        String nom = "Tablette CSPS " + UUID.randomUUID().toString().substring(0, 8);

        var premiere = mockMvc.perform(post("/api/v1/sync/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"deviceName\":\"%s\"}".formatted(user, nom)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").isNotEmpty())
                .andReturn();
        String deviceId = JsonPath.read(premiere.getResponse().getContentAsString(), "$.deviceId");

        mockMvc.perform(post("/api/v1/sync/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"deviceName\":\"%s\"}".formatted(user, nom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId));

        Integer appareils = jdbc.queryForObject(
                "SELECT count(*) FROM sync.device WHERE id = ?", Integer.class,
                UUID.fromString(deviceId));
        assertThat(appareils).isEqualTo(1);

        // Un autre nom pour le même utilisateur = un autre appareil.
        var autre = mockMvc.perform(post("/api/v1/sync/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"deviceName\":\"%s\"}"
                                .formatted(user, "Portable " + UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn();
        String autreId = JsonPath.read(autre.getResponse().getContentAsString(), "$.deviceId");
        assertThat(autreId).isNotEqualTo(deviceId);
    }

    // ------------------------------------------------------------------
    // Lot offline complet + idempotence de rejeu
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Lot offline complet : patient + encounter + observation → tout APPLIED, visible, offline")
    void lotOfflineComplet() throws Exception {
        UUID user = UUID.randomUUID();
        String deviceId = declarerAppareil(user, "Lot-" + UUID.randomUUID().toString().substring(0, 6));

        UUID patientCle = v7();      // clientRequestId = UUID patient local côté PWA
        UUID encounterId = v7();
        UUID observationId = v7();

        String reponse = uplink(lot(UUID.fromString(deviceId),
                opPatient(v7(), patientCle, user, "ILBOUDO", "Fatimata", "2010-04-12",
                        "+22670" + UUID.randomUUID().toString().substring(0, 6), false),
                opEncounter(v7(), encounterId, patientCle),
                opObservation(v7(), observationId, encounterId, patientCle)));

        assertThat((Integer) JsonPath.read(reponse, "$.results.length()")).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat((String) JsonPath.read(reponse, "$.results[%d].result".formatted(i)))
                    .isEqualTo("APPLIED");
        }

        // Le patient est visible via le contrôleur existant (identity).
        String patientId = JsonPath.read(reponse, "$.results[0].detail.entityId");
        mockMvc.perform(get("/api/v1/patients/%s".formatted(patientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patientId))
                .andExpect(jsonPath("$.phReference").value(matchesPattern("PH-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.names[0].family").value("ILBOUDO"));

        // Clinical : append-only, créé offline, synced_at laissé NULL, FK tenue.
        Map<String, Object> encounter = jdbc.queryForMap("""
                SELECT patient_id, created_via, synced_at, encounter_class
                FROM clinical.encounter WHERE id = ?
                """, encounterId);
        assertThat(encounter.get("created_via")).isEqualTo("offline");
        assertThat(encounter.get("synced_at")).isNull();
        // RÉSOLUTION : patientId local (clientRequestId) → UUID serveur.
        assertThat((UUID) encounter.get("patient_id")).isEqualTo(UUID.fromString(patientId));

        Integer observations = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.observation WHERE id = ? AND encounter_id = ?",
                Integer.class, observationId, encounterId);
        assertThat(observations).isEqualTo(1);

        // Outbox transactionnel : un événement par entité appliquée.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync.outbox WHERE event_type = 'sync.patient.created' AND aggregate_id = ?",
                Integer.class, UUID.fromString(patientId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync.outbox WHERE event_type = 'sync.encounter.created' AND aggregate_id = ?",
                Integer.class, encounterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync.outbox WHERE event_type = 'sync.observation.created' AND aggregate_id = ?",
                Integer.class, observationId)).isEqualTo(1);

        // Audit : PatientCreated (identity) + SYNC_OP_APPLIED (clinical).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry WHERE entity = 'encounter'
                  AND action = 'SYNC_OP_APPLIED' AND entity_id = ?
                """, Integer.class, encounterId)).isGreaterThanOrEqualTo(1);

        // L'op idempotente est journalisée.
        Integer ops = jdbc.queryForObject(
                "SELECT count(*) FROM sync.op WHERE result = 'APPLIED'", Integer.class);
        assertThat(ops).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Rejeu du même lot (mêmes opId) : mêmes résultats, aucune donnée dupliquée")
    void rejeuLotIdempotent() throws Exception {
        UUID patientCle = v7();
        UUID encounterId = v7();
        UUID observationId = v7();
        UUID opP = v7();
        UUID opE = v7();
        UUID opO = v7();

        String corps = lot(null,
                opPatient(opP, patientCle, UUID.randomUUID(), "SAWADOGO", "Rasmane",
                        "1979-11-03", "+22676" + UUID.randomUUID().toString().substring(0, 6), false),
                opEncounter(opE, encounterId, patientCle),
                opObservation(opO, observationId, encounterId, patientCle));

        String premiere = uplink(corps);
        String rejeu = uplink(corps);

        // Les réponses portent exactement les mêmes verdicts et détails.
        assertThat(objectMapper.readTree(rejeu)).isEqualTo(objectMapper.readTree(premiere));
        assertThat((String) JsonPath.read(rejeu, "$.results[0].result")).isEqualTo("APPLIED");

        // Aucune donnée dupliquée : compte en base strictement identique.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity.patient WHERE client_request_id = ?",
                Integer.class, patientCle)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM clinical.encounter WHERE id = ?",
                Integer.class, encounterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM clinical.observation WHERE id = ?",
                Integer.class, observationId)).isEqualTo(1);
        // Une seule op journalisée par opId, un seul événement outbox.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync.op WHERE op_id IN (?, ?, ?)",
                Integer.class, opP, opE, opO)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync.outbox WHERE aggregate_id IN (?, ?, ?)",
                Integer.class,
                UUID.fromString(JsonPath.read(rejeu, "$.results[0].detail.entityId")),
                encounterId, observationId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Conflit patient (409 = contrat UX) sans casser le lot
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Doublon patient → CONFLICT + candidats MASQUÉS en posture ouverte (S4/Q42), le lot continue")
    void doublonConflitLeLotContinue() throws Exception {
        String telephone = "+22670" + UUID.randomUUID().toString().substring(0, 6);
        String existant = creerPatient("TAPSOBA", "Roukiata", "1994-06-18", telephone);

        UUID encounterId = v7();
        String reponse = uplink(lot(null,
                opPatient(v7(), v7(), UUID.randomUUID(), "TAPSOBA", "Roukiata",
                        "1994-06-18", telephone, false),
                opEncounter(v7(), encounterId, UUID.fromString(existant))));

        assertThat((String) JsonPath.read(reponse, "$.results[0].result")).isEqualTo("CONFLICT");
        assertThat((String) JsonPath.read(reponse, "$.results[0].detail.reason"))
                .contains("déjà enregistré");
        // Suggestion 4 / Q42 : en posture ouverte (uplink anonyme), l'appareil
        // reçoit le COMPTEUR, pas les dossiers. Le contrat complet (candidats
        // embarqués pour un jeton staff portant patient:lire) vit côté
        // authentifié — AuthRbacIT.doublon409CandidatsCompletsPourOperateur ;
        // les deux voies partagent la même résolution ContexteAppelant.permission.
        assertThat((Boolean) JsonPath.read(reponse, "$.results[0].detail.candidatesRedacted"))
                .isTrue();
        assertThat((Integer) JsonPath.read(reponse, "$.results[0].detail.candidatesCount"))
                .isGreaterThanOrEqualTo(1);
        // Défense : aucun tableau de candidats ne fuit dans la réponse.
        assertThat(reponse).doesNotContain("\"candidates\":[");

        // Le reste du lot s'applique malgré le conflit.
        assertThat((String) JsonPath.read(reponse, "$.results[1].result")).isEqualTo("APPLIED");
        Integer encounters = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.encounter WHERE id = ?", Integer.class, encounterId);
        assertThat(encounters).isEqualTo(1);

        // Rien n'a été créé pour l'op en conflit.
        Integer conflits = jdbc.queryForObject(
                "SELECT count(*) FROM sync.op WHERE result = 'CONFLICT'", Integer.class);
        assertThat(conflits).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Rejets propres : entité inconnue, observation orpheline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Entité inconnue → REJECTED avec motif, les autres ops passent")
    void entiteInconnueRejetee() throws Exception {
        UUID patientCle = v7();
        String reponse = uplink(lot(null,
                """
                {"opId":"%s","entity":"medication_request","entityId":"%s",
                 "payload":{"medicationText":"Paracétamol"}}
                """.formatted(v7(), v7()),
                opPatient(v7(), patientCle, UUID.randomUUID(), "KONE", "Boubacar",
                        "2005-01-30", "+22676" + UUID.randomUUID().toString().substring(0, 6), false)));

        assertThat((String) JsonPath.read(reponse, "$.results[0].result")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(reponse, "$.results[0].detail.reason"))
                .contains("Entité inconnue");

        assertThat((String) JsonPath.read(reponse, "$.results[1].result")).isEqualTo("APPLIED");
        String patientId = JsonPath.read(reponse, "$.results[1].detail.entityId");
        mockMvc.perform(get("/api/v1/patients/%s".formatted(patientId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Observation référencée à un encounter inexistant → REJECTED (FK), le lot tient")
    void observationOrphelineRejetee() throws Exception {
        String patient = creerPatient("BAMBARA", "Salif", "1962-02-02",
                "+22670" + UUID.randomUUID().toString().substring(0, 6));
        UUID patientId = UUID.fromString(patient);

        UUID encounterId = v7();
        UUID conditionId = v7();
        UUID observationId = v7();

        String reponse = uplink(lot(null,
                opEncounter(v7(), encounterId, patientId),
                opObservation(v7(), observationId, v7(), patientId),   // encounter fantôme
                opCondition(v7(), conditionId, encounterId, patientId)));

        assertThat((String) JsonPath.read(reponse, "$.results[0].result")).isEqualTo("APPLIED");
        assertThat((String) JsonPath.read(reponse, "$.results[1].result")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(reponse, "$.results[1].detail.reason"))
                .contains("Encounter référencé inexistant");
        assertThat((String) JsonPath.read(reponse, "$.results[2].result")).isEqualTo("APPLIED");

        Integer orpheline = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.observation WHERE id = ?",
                Integer.class, observationId);
        assertThat(orpheline).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM clinical.encounter WHERE id = ?",
                Integer.class, encounterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM clinical.condition WHERE id = ?",
                Integer.class, conditionId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Delta — miroir descendant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Delta : 2 patients créés remontent, curseur émis, re-delta vide")
    void deltaMiroirAvecCurseur() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String id1 = creerPatient("COMPAORE", "Assetou", "1993-03-03", "+22670" + suffixe + "1");
        String id2 = creerPatient("ZONGO", "Boureima", "1988-08-08", "+22670" + suffixe + "2");

        var premierePage = mockMvc.perform(get("/api/v1/sync/delta")
                        .param("entities", "patient"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.cursor").isNotEmpty())
                .andReturn();
        String corps = premierePage.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Les deux patients créés remontent, avec leur miroir complet.
        List<Map<String, Object>> patients = JsonPath.read(corps, "$.patients");
        Map<String, Object> miroir1 = patients.stream()
                .filter(p -> id1.equals(p.get("id"))).findFirst().orElseThrow();
        assertThat((String) miroir1.get("phReference")).matches("PH-\\d{4}-\\d{6}");
        assertThat((Boolean) miroir1.get("active")).isTrue();
        List<Map<String, Object>> noms = (List<Map<String, Object>>) miroir1.get("names");
        assertThat(noms.get(0).get("family")).isEqualTo("COMPAORE");
        List<Map<String, Object>> telecoms = (List<Map<String, Object>>) miroir1.get("telecoms");
        assertThat(telecoms.get(0).get("value")).isEqualTo("+22670" + suffixe + "1");
        assertThat(patients.stream().map(p -> p.get("id"))).contains(id1, id2);

        // Re-delta avec le nouveau curseur : vide (chaque ligne vue une fois).
        String curseur = JsonPath.read(corps, "$.cursor");
        mockMvc.perform(get("/api/v1/sync/delta")
                        .param("cursor", curseur)
                        .param("entities", "patient"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patients", empty()))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.cursor").value(curseur));

        // Curseur malformé / entité inconnue : 400 RFC 7807.
        mockMvc.perform(get("/api/v1/sync/delta").param("cursor", "nimporte-quoi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requête de synchronisation refusée"));
        mockMvc.perform(get("/api/v1/sync/delta").param("entities", "invoice"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Garde-fous protocole
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Garde-fous : opId non v7 rejeté, deviceId inconnu = 400, ops sans charge rejetée")
    void gardesDuProtocole() throws Exception {
        // opId en v4 → REJECTED (contrat : v7 côté client).
        String reponse = uplink(lot(null, """
                {"opId":"%s","entity":"patient","payload":{
                  "clientRequestId":"%s","names":[{"family":"X","given":"Y"}]}}
                """.formatted(UUID.randomUUID(), v7())));
        assertThat((String) JsonPath.read(reponse, "$.results[0].result")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(reponse, "$.results[0].detail.reason")).contains("UUID v7");

        // Charge sans clientRequestId pour un patient offline → REJECTED.
        String sansCle = uplink(lot(null, """
                {"opId":"%s","entity":"patient","payload":{
                  "names":[{"family":"X","given":"Y"}]}}
                """.formatted(v7())));
        assertThat((String) JsonPath.read(sansCle, "$.results[0].result")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(sansCle, "$.results[0].detail.reason"))
                .contains("Charge patient invalide");

        // Appareil jamais déclaré → 400, le lot n'est même pas entamé.
        mockMvc.perform(post("/api/v1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\",\"ops\":[%s]}"
                                .formatted(v7(), opPatient(v7(), v7(), UUID.randomUUID(),
                                        "DIALLO", "Fati", "1985-03-09", "+22676554433", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requête de synchronisation refusée"));
    }
}
