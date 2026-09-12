package bf.publichealth.securite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.jayway.jsonpath.JsonPath;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — P0.9 : AUTH INTERNE + RBAC (corrections I1/I2/I3
 * de l'audit de fidélité).
 *
 * <p>La chaîne COMPLÈTE, prouvée sur PostgreSQL réel :</p>
 * <ul>
 *   <li>anonyme → 401 partout (l'API n'est plus ouverte — I1) ;</li>
 *   <li>login staff (BCrypt, V14) → jeton HS256 auto-émis ;</li>
 *   <li>FiltrePermissions : pharmacien ne CONSULTE pas (403),
 *       infirmier ne lit pas les statistiques (403) — la matrice
 *       n'est plus décorative (I2) ;</li>
 *   <li>admin MFA : défi renvoyé SANS jeton puis login complet ;</li>
 *   <li>patient par OTP : périmètre STRICTEMENT personnel —
 *       son dossier 200, celui d'un autre 403 ;</li>
 *   <li>consultation complète PERSISTÉE (motif, constantes, notes —
 *       I4) ;</li>
 *   <li>stock : réception puis RUPTURE refusée en 409 (I8) ;</li>
 *   <li>décès : dossier scellé, consultation refusée (I15).</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "securite.jwt.actif=true",
        "securite.jwt.secret=secret-hs256-de-test-public-health-0123456789",
        "fedapay.webhook-secret=secret-de-test",
        "auth.demo-otp-visible=true"
})
@AutoConfigureMockMvc
class AuthRbacIT {

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

    /** Structure CSPS semée par V14. */
    private static final String CSPS = "11111111-1111-4111-8111-111111111101";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // Login staff
    // ------------------------------------------------------------------

    private String login(String email) throws Exception {
        var reponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"motDePasse\":\"Demo1234!\"}".formatted(email)))
                .andReturn();
        String corps = reponse.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(200, reponse.getResponse().getStatus(),
                "login " + email + " → " + corps);
        return JsonPath.read(corps, "$.jeton");
    }

    @Test
    @DisplayName("I1 : anonyme → 401 partout, l'API n'est plus un registre ouvert")
    void anonymeRefusePartout() throws Exception {
        mockMvc.perform(get("/api/v1/patients?family=WEDRAOGO"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"diagnosticCode\":\"A00\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("I2/I3 : login infirmier (BCrypt) → jeton → lecture MPI autorisée")
    void loginInfirmierEtLectureMpi() throws Exception {
        String jeton = login("infirmier@demo.bf");
        mockMvc.perform(get("/api/v1/patients?family=AAAA")
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("I2 : pharmacien ne CONSULTE pas — 403 depuis la matrice, pas depuis la nav")
    void pharmacienInterditDeConsulter() throws Exception {
        String jeton = login("pharmacien@demo.bf");
        String patient = creerPatient(login("infirmier@demo.bf"), null);
        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Accès refusé"));
    }

    @Test
    @DisplayName("I2 : infirmier ne lit pas les statistiques SNIS (audit:lire requis)")
    void infirmierInterditStatistiques() throws Exception {
        String jeton = login("infirmier@demo.bf");
        mockMvc.perform(get("/api/v1/statistiques/snis?structureId=" + CSPS)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/statistiques/snis?structureId=" + CSPS)
                        .header("Authorization", "Bearer " + login("superviseur@demo.bf")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periode").exists());
    }

    @Test
    @DisplayName("I3 : admin MFA — défi sans jeton, puis login complet et back-office")
    void adminMfaDeuxEtapes() throws Exception {
        // Étape 1 : sans code → 401 + défi (code démo renvoyé, posture documentée).
        var defi = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@demo.bf\",\"motDePasse\":\"Demo1234!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.mfaRequise").value(true))
                .andReturn();
        String code = JsonPath.read(defi.getResponse().getContentAsString(), "$.codeDemo");
        assertThat(code).isNotBlank();

        // Étape 2 : avec le code → jeton → back-office autorisé.
        String jeton = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@demo.bf\",\"motDePasse\":\"Demo1234!\",\"codeMfa\":\"%s\"}"
                                .formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utilisateur.role").value("admin"))
                .andReturn().getResponse().getContentAsString();
        String jetonAdmin = JsonPath.read(jeton, "$.jeton");
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + jetonAdmin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("I4 : consultation complète persistée — motif, constantes, notes reviennent")
    void consultationCompletePersistee() throws Exception {
        String jeton = login("medecin@demo.bf");
        String patient = creerPatient(jeton, "70" + telephoneAleatoire());
        var reponse = mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();
        String consultation = JsonPath.read(reponse.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/consultations?patientId=" + patient)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].motif").value("Fièvre et céphalées depuis 2 jours"))
                .andExpect(jsonPath("$[0].constantes.TA_SYSTOLIQUE").value(120))
                .andExpect(jsonPath("$[0].constantes.POIDS").value(62))
                .andExpect(jsonPath("$[0].notes").exists());

        mockMvc.perform(get("/api/v1/consultations/" + consultation)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosticCode").value("B54"));
    }

    @Test
    @DisplayName("I13 : patient par OTP — son dossier 200, celui d'un autre 403, aucune écriture")
    void patientOtpPerimetreStrict() throws Exception {
        String telephone = telephoneAleatoire();
        String patient = creerPatient(login("infirmier@demo.bf"), telephone);
        String autre = creerPatient(login("infirmier@demo.bf"), null);

        // Étape 1 : demande de code — renvoyé en démo (aucune passerelle SMS livrée).
        var demande = mockMvc.perform(post("/api/v1/auth/patient/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\"}".formatted(telephone)))
                .andExpect(status().isAccepted())
                .andReturn();
        String code = JsonPath.read(demande.getResponse().getContentAsString(), "$.codeDemo");
        assertThat(code).isNotBlank();

        // Étape 2 : vérification → jeton autoporteur patient_id.
        var verification = mockMvc.perform(post("/api/v1/auth/patient/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\",\"code\":\"%s\"}".formatted(telephone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(patient))
                .andReturn();
        String jeton = JsonPath.read(verification.getResponse().getContentAsString(), "$.jeton");

        // SON dossier : 200 (et l'audit trace la lecture).
        mockMvc.perform(get("/api/v1/patients/" + patient)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());
        // Le dossier d'un autre : 403 — périmètre vérifié CÔTÉ API.
        mockMvc.perform(get("/api/v1/patients/" + autre)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden());
        // Aucune écriture clinique pour un patient.
        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isForbidden());

        // La lecture de SON dossier est bien AUDITÉE (I16).
        Integer traces = jdbc.queryForObject(
                "SELECT count(*) FROM audit.entry WHERE action = 'PATIENT_READ' AND entity_id = ?::uuid",
                Integer.class, patient);
        assertThat(traces).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("I8 : stock réel — réception, puis dispensation en rupture refusée (409)")
    void stockRuptureRefusee() throws Exception {
        String pharmacien = login("pharmacien@demo.bf");
        String medecin = login("medecin@demo.bf");

        // Réception : 10 comprimés seulement.
        mockMvc.perform(post("/api/v1/stock/mouvements")
                        .header("Authorization", "Bearer " + pharmacien)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"structureId":"%s","medicationCode":"RUPT-%s",
                                 "medicationLabel":"Artéméther-Luméfantrine",
                                 "type":"reception","quantite":10,"motif":"réception CAMEG"}
                                """.formatted(CSPS, "TEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantite").value(10));

        // Prescription de 50 sur ce médicament.
        String patient = creerPatient(medecin, null);
        String prescription = mockMvc.perform(post("/api/v1/prescriptions")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","facilityId":"%s",
                                 "prescriberId":"%s",
                                 "items":[{"medicationCode":"RUPT-TEST","medicationLabel":"Artéméther-Luméfantrine",
                                   "dose":"1 comprimé","form":"comprimé","route":"oral",
                                   "frequency":"2 fois par jour","durationDays":5,"quantityPrescribed":50}]}
                                """.formatted(patient, CSPS, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String prescriptionId = JsonPath.read(prescription, "$.id");
        String ligneId = JsonPath.read(prescription, "$.items[0].id");

        // Dispensation de 50 : RUPTURE → 409, rien n'est débité.
        mockMvc.perform(post("/api/v1/prescriptions/%s/items/%s/dispense"
                        .formatted(prescriptionId, ligneId))
                        .header("Authorization", "Bearer " + pharmacien)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Rupture de stock"));

        Integer solde = jdbc.queryForObject(
                "SELECT quantite FROM pharmacie.stock_item WHERE structure_id = ?::uuid "
                        + "AND medication_code = 'RUPT-TEST'",
                Integer.class, CSPS);
        assertThat(solde).isEqualTo(10);
    }

    @Test
    @DisplayName("I15 : décès déclaré → dossier scellé, consultation refusée")
    void decesScelleLeDossier() throws Exception {
        String medecin = login("medecin@demo.bf");
        String patient = creerPatient(medecin, null);

        mockMvc.perform(post("/api/v1/patients/%s/deces".formatted(patient))
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cause\":\"Paludisme grave\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Dossier scellé : décès déclaré — aucune consultation possible"));
    }

    // ------------------------------------------------------------------
    // Suggestion 4 — Q42 : 409 aveuglé + lectures FHIR auditées
    // ------------------------------------------------------------------

    @Test
    @DisplayName("S4/Q42 : 409 doublons — candidats COMPLETS pour un opérateur patient:lire, création refusée au jeton patient")
    void doublon409CandidatsCompletsPourOperateur() throws Exception {
        // agent_saisie : le rôle admission du CSPS — patient:ecrire ET patient:lire.
        String jeton = login("agent.saisie@demo.bf");
        String nunp = "NUNP-" + UUID.randomUUID().toString().substring(0, 8);
        String tel = "+22670" + UUID.randomUUID().toString().substring(0, 6);

        // Un dossier existe déjà (créé par un autre opérateur).
        String existant = creerPatientAvecNunp(login("infirmier@demo.bf"), nunp, tel);

        // Même identité → 409 avec les candidats EN CLAIR pour l'opérateur
        // identifié : le contrat UX (ADR-003) est INTACT côté authentifié.
        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                        {"gender":"female","birthDate":"1992-02-20",
                                         "names":[{"use":"official","family":"TRAORE","given":"Mariam"}],
                                         "telecoms":[{"system":"phone","value":"%s","use":"mobile"}],
                                         "identifiers":[{"system":"NUNP","value":"%s"}]}
                                        """.formatted(tel, nunp)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.candidates").isArray())
                .andExpect(jsonPath("$.candidates[0].method").value("EXACT"))
                .andExpect(jsonPath("$.candidates[0].blocking").value(true))
                .andExpect(jsonPath("$.candidates[0].score").value(1.0))
                .andExpect(jsonPath("$.candidates[0].id").value(existant))
                .andExpect(jsonPath("$.candidates[0].phReference").isNotEmpty())
                // Pas de masquage pour un opérateur autorisé.
                .andExpect(jsonPath("$.candidatesRedacted").doesNotExist());

        // Et un JETON PATENT n'atteint même PAS la création : le périmètre du
        // FiltrePermissions le refuse (403) avant le service. La défense est
        // en profondeur — si le filtre laissait passer, le handler masquerait
        // quand même (masquage anonyme prouvé en posture ouverte par
        // PatientIdentityIT.doublonExactParNunp).
        String telephone = telephoneAleatoire();
        creerPatient(login("infirmier@demo.bf"), telephone);
        var demande = mockMvc.perform(post("/api/v1/auth/patient/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\"}".formatted(telephone)))
                .andExpect(status().isAccepted())
                .andReturn();
        String code = JsonPath.read(demande.getResponse().getContentAsString(), "$.codeDemo");
        String jetonPatient = JsonPath.read(mockMvc.perform(post("/api/v1/auth/patient/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telephone\":\"%s\",\"code\":\"%s\"}".formatted(telephone, code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.jeton");

        mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jetonPatient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                        {"gender":"female","birthDate":"1992-02-20",
                                         "names":[{"use":"official","family":"TRAORE","given":"Mariam"}],
                                         "telecoms":[{"system":"phone","value":"%s","use":"mobile"}],
                                         "identifiers":[{"system":"NUNP","value":"%s"}]}
                                        """.formatted(tel, nunp)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.candidates").doesNotExist());
    }

    @Test
    @DisplayName("S4 : façade FHIR — chaque lecture réussie est AUDITÉE (FHIR_READ / FHIR_SEARCH)")
    void fhirLecturesAuditees() throws Exception {
        String jeton = login("medecin@demo.bf");
        String famille = "FHIR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String patient = creerPatientFamille(jeton, famille);

        // 1. Lecture unitaire : GET /fhir/R4/Patient/{id} → FHIR_READ tracé.
        mockMvc.perform(get("/fhir/R4/Patient/" + patient)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Patient"));
        Integer lectures = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'FHIR_READ' AND entity = 'fhir_patient'
                  AND entity_id = ?::uuid
                """, Integer.class, patient);
        assertThat(lectures).isGreaterThanOrEqualTo(1);

        // 2. Recherche non vide : GET /fhir/R4/Patient?family=… → FHIR_SEARCH
        //    avec le nombre de ressources divulguées.
        mockMvc.perform(get("/fhir/R4/Patient").param("family", famille)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.total").value(1));
        Integer recherches = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'FHIR_SEARCH' AND entity = 'fhir_patient'
                  AND details::jsonb ->> 'resultats' = '1'
                """, Integer.class);
        assertThat(recherches).isGreaterThanOrEqualTo(1);

        // 3. Bundle VIDE : aucune divulgation → AUCUNE entrée parasite.
        mockMvc.perform(get("/fhir/R4/Patient").param("family", "INEX" + famille)
                        .header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        Integer vides = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'FHIR_SEARCH' AND entity = 'fhir_patient'
                  AND details::jsonb ->> 'resultats' = '0'
                """, Integer.class);
        assertThat(vides).isZero();
    }

    // ------------------------------------------------------------------
    // Chargeurs
    // ------------------------------------------------------------------

    /** Création avec NUNP + téléphone explicites (doublon EXACT garanti). */
    private String creerPatientAvecNunp(String jeton, String nunp, String tel) throws Exception {
        var result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                        {"gender":"female","birthDate":"1992-02-20",
                                         "names":[{"use":"official","family":"TRAORE","given":"Mariam"}],
                                         "telecoms":[{"system":"phone","value":"%s","use":"mobile"}],
                                         "identifiers":[{"system":"NUNP","value":"%s"}]}
                                        """.formatted(tel, nunp)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** Création avec un patronyme arbitraire (recherche FHIR ciblée). */
    private String creerPatientFamille(String jeton, String famille) throws Exception {
        var result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                        {"gender":"male","birthDate":"1988-05-17",
                                         "names":[{"use":"official","family":"%s","given":"Amadou"}]}
                                        """.formatted(famille)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String creerPatient(String jeton, String telephone) throws Exception {
        String alea = UUID.randomUUID().toString().replace("-", "");
        String naissance = "%d-%02d-%02d".formatted(
                1960 + (alea.charAt(12) % 40), 1 + (alea.charAt(13) % 12),
                1 + (alea.charAt(14) % 28));
        String telecom = telephone == null ? "" : """
                ,"telecoms":[{"system":"phone","value":"%s","use":"mobile"}]""".formatted(telephone);
        var result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gender":"female","birthDate":"%s"%s,
                                 "names":[{"use":"official","family":"%s","given":"%s"}]}
                                """.formatted(naissance, telecom,
                                        alea.substring(0, 8).toUpperCase(),
                                        alea.substring(8, 12).toUpperCase())))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String corpsConsultation(String patientId) {
        return """
                {"patientId":"%s","facilityId":"%s",
                 "motif":"Fièvre et céphalées depuis 2 jours",
                 "diagnosticCode":"B54","diagnosticLabel":"Paludisme à P. falciparum",
                 "notes":"TDR positif, patient couché",
                 "constantes":{"taSystolique":120,"taDiastolique":80,
                               "temperatureC":38.6,"poidsKg":62}}
                """.formatted(patientId, CSPS);
    }

    private static String telephoneAleatoire() {
        return String.format("%08d", new java.util.Random().nextInt(100_000_000));
    }
}
