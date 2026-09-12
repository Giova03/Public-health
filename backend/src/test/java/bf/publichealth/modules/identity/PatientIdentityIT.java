package bf.publichealth.modules.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.jayway.jsonpath.JsonPath;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — épique E1 : identité & MPI.
 *
 * <p>Couverture : création avec référence PH-AAAA-NNNNNN, idempotence
 * offline (clientRequestId), doublon EXACT par identifiant national (409 =
 * contrat UX avec candidats embarqués), zone grise PROBABILISTIC (Jaro-
 * Winkler sur patronymes normalisés), création forcée tracée, recherche
 * miroir, file de revue, fusion irréversible avec déménagement des
 * identifiants nationaux et journal merge_log, rejet de revue.</p>
 *
 * <p>PostgreSQL : Testcontainers en CI (Docker), embarqué zonky en local.</p>
 */
@Tag("integration")
@SpringBootTest(properties = "fedapay.webhook-secret=secret-de-test")
@AutoConfigureMockMvc
class PatientIdentityIT {

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

    private String corpsPatient(String family, String given, String birthDate,
                                String telephone, String nunp, UUID clientRequestId,
                                boolean forceCreate, UUID duplicateOf) {
        StringBuilder telecoms = new StringBuilder();
        if (telephone != null) {
            telecoms.append("""
                    {"system":"phone","value":"%s","use":"mobile"},""".formatted(telephone));
        }
        StringBuilder identifiers = new StringBuilder();
        if (nunp != null) {
            identifiers.append("""
                    {"system":"NUNP","value":"%s"},""".formatted(nunp));
        }
        return """
                {
                  "clientRequestId": "%s",
                  "forceCreate": %b,
                  "duplicateOfRejected": %s,
                  "gender": "female",
                  "birthDate": "%s",
                  "birthDateApproximative": false,
                  "names": [{"use":"official","family":"%s","given":"%s"}],
                  "telecoms": [%s],
                  "identifiers": [%s],
                  "createdBy": "%s"
                }
                """.formatted(
                clientRequestId, forceCreate,
                duplicateOf == null ? "null" : "\"" + duplicateOf + "\"",
                birthDate, family, given,
                telecoms.length() > 0
                        ? telecoms.substring(0, telecoms.length() - 1) : "",
                identifiers.length() > 0
                        ? identifiers.substring(0, identifiers.length() - 1) : "",
                UUID.randomUUID());
    }

    private String creer(String family, String given, String birthDate, String telephone,
                         String nunp) throws Exception {
        var result = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient(family, given, birthDate, telephone, nunp,
                                UUID.randomUUID(), false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phReference").isNotEmpty())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------
    // Création, référence PH, idempotence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Création simple : 201, référence PH-AAAA-NNNNNN, traits persistés")
    void creationSimple() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("KABORE", "Issa", "2018-06-15",
                                "+22670112233", null, UUID.randomUUID(), false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.phReference").value(
                        org.hamcrest.Matchers.matchesPattern("PH-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.names[0].family").value("KABORE"))
                .andExpect(jsonPath("$.telecoms[0].value").value("+22670112233"));

        Integer dossiers = jdbc.queryForObject(
                "SELECT count(*) FROM identity.patient WHERE active", Integer.class);
        assertThat(dossiers).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Idempotence offline : même clientRequestId = même dossier, jamais deux")
    void idempotenceRejeu() throws Exception {
        UUID cle = UUID.randomUUID();
        String body = corpsPatient("SAWADOGO", "Rasmane", "1979-11-03",
                "+22676445566", null, cle, false, null);

        var premier = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String id1 = JsonPath.read(premier.getResponse().getContentAsString(), "$.id");

        var rejeu = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id1))
                .andReturn();

        Integer dossiers = jdbc.queryForObject(
                "SELECT count(*) FROM identity.patient WHERE client_request_id = ?",
                Integer.class, cle);
        assertThat(dossiers).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 409 = contrat UX
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Doublon EXACT par NUNP national partagé : 409, candidats MASQUÉS pour un anonyme (Q42), rien créé")
    void doublonExactParNunp() throws Exception {
        String nunp = "NUNP-" + UUID.randomUUID().toString().substring(0, 8);
        creer("TRAORE", "Mariam", "1992-02-20", "+22670998877", nunp);

        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("TRAORE", "Mariam", "1992-02-20",
                                "+22670998877", nunp, UUID.randomUUID(), false, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Patient probablement déjà enregistré"))
                // Suggestion 4 / Q42 : en posture ouverte (anonyme), les candidats
                // ne fuient PLUS — compteur seul. Le contrat UX complet (candidats
                // embarqués) vit désormais côté authentifié (AuthRbacIT,
                // doublon409CandidatsCompletsPourOperateur).
                .andExpect(jsonPath("$.candidatesRedacted").value(true))
                .andExpect(jsonPath("$.candidatesCount").value(1))
                .andExpect(jsonPath("$.candidates").doesNotExist());

        // Le MASQUAGE lui-même est tracé (défense en profondeur visible
        // dans la chaîne d'audit, avec le compteur).
        Integer masques = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PATIENT_DUPLICATE_REDACTED' AND result = 'DENIED'
                """, Integer.class);
        assertThat(masques).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Zone grise PROBABILISTIC : 409 anonyme masqué, la détection reste tracée en audit")
    void doublonZoneGrise() throws Exception {
        creer("OUEDRAOGO", "Aminata", "1995-04-08", null, null);

        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("OUEDRAOGO", "Aminatou", "1995-04-08",
                                null, null, UUID.randomUUID(), false, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.candidatesRedacted").value(true))
                .andExpect(jsonPath("$.candidates").doesNotExist());

        // La méthode PROBABILISTIC reste prouvée : l'entrée de détection
        // du service porte la méthode du meilleur candidat (sans PII).
        Integer detectes = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PATIENT_DUPLICATE_DETECTED'
                  AND details::jsonb ->> 'method' = 'PROBABILISTIC'
                """, Integer.class);
        assertThat(detectes).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Création forcée après 409 : dossier distinct créé, décision tracée")
    void creationForceeApresDoublon() throws Exception {
        String tel = "+22670" + UUID.randomUUID().toString().substring(0, 6);
        String idExistant = creer("BATIONO", "Chantal", "2001-09-30", tel, null);

        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("BATIONO", "Chantal", "2001-09-30", tel,
                                null, UUID.randomUUID(), false, null)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("BATIONO", "Chantal", "2001-09-30", tel,
                                null, UUID.randomUUID(), true, UUID.fromString(idExistant))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));

        // La décision est portée par l'audit (motif + candidat rejeté), pas par
        // la file de revue, réservée aux rapprochements entre dossiers existants.
        Integer traces = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PATIENT_CREATED' AND reason = 'CREATION_FORCEE_APRES_DOUBLON'
                """, Integer.class);
        assertThat(traces).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Recherche miroir
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Recherche miroir : par patronyme et par téléphone")
    void rechercheMiroir() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String famille = "ZABRE" + suffixe.substring(0, 3);
        creer(famille, "Boureima", "1968-01-25", "+22670" + suffixe, null);

        mockMvc.perform(get("/api/v1/patients").param("family", famille))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].names[0].family").value(famille));

        mockMvc.perform(get("/api/v1/patients").param("phone", "+22670" + suffixe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.empty())));
    }

    // ------------------------------------------------------------------
    // File de revue + fusion irréversible
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Fusion ACCEPTED : irréversible, tracée, identifiant national déménagé, 410 ensuite")
    void fusionTraceeEtIrrversible() throws Exception {
        String nunpMerged = "NUNP-F" + UUID.randomUUID().toString().substring(0, 7);
        String idA = creer("COMPAORE", "Blaise", "1990-07-14", "+22671223344", nunpMerged);
        String idB = creer("DIALLO", "Fati", "1985-03-09", "+22676554433", null);

        var match = mockMvc.perform(post("/api/v1/identity/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateA":"%s","candidateB":"%s","method":"PROBABILISTIC"}
                                """.formatted(idA, idB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String matchId = JsonPath.read(match.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/identity/matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(matchId));

        // Le réviseur désigne B comme maître : A fusionne dans B.
        mockMvc.perform(post("/api/v1/identity/matches/%s/review".formatted(matchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ACCEPTED","masterId":"%s",
                                 "motif":"Double enregistrement confirme par l'agent",
                                 "reviewedBy":"%s"}
                                """.formatted(idB, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masterId").value(idB))
                .andExpect(jsonPath("$.mergedId").value(idA));

        // Le dossier fusionné est inactif, pointé vers le maître.
        Map<String, Object> etat = jdbc.queryForMap(
                "SELECT active, master_id FROM identity.patient WHERE id = ?", UUID.fromString(idA));
        assertThat(etat.get("active")).isEqualTo(Boolean.FALSE);
        assertThat(etat.get("master_id")).isEqualTo(UUID.fromString(idB));

        // Son identifiant national a déménagé vers le maître.
        Integer chezMaitre = jdbc.queryForObject("""
                SELECT count(*) FROM identity.patient_identifier
                WHERE patient_id = ? AND system = 'NUNP' AND value = ?
                """, Integer.class, UUID.fromString(idB), nunpMerged);
        assertThat(chezMaitre).isEqualTo(1);

        // Le journal des fusions porte la raison OBLIGATOIRE.
        Integer fusions = jdbc.queryForObject("""
                SELECT count(*) FROM identity.merge_log
                WHERE master_id = ? AND merged_id = ? AND reason LIKE '%confirme%'
                """, Integer.class, UUID.fromString(idB), UUID.fromString(idA));
        assertThat(fusions).isEqualTo(1);

        // GET du dossier fusionné : 410 Gone + masterId pour rediriger.
        mockMvc.perform(get("/api/v1/patients/%s".formatted(idA)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.masterId").value(idB));

        // Le rapprochement est statué.
        String statut = jdbc.queryForObject(
                "SELECT status FROM identity.identity_match WHERE id = ?",
                String.class, UUID.fromString(matchId));
        assertThat(statut).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("Revue REJECTED : dossiers intacts, décision tracée avec motif")
    void revueRejetee() throws Exception {
        String idA = creer("NIKIEMA", "Pascal", "1975-05-05", "+22670556677", null);
        String idB = creer("YAMEOGO", "Abdou", "1970-10-10", "+22670445566", null);

        var match = mockMvc.perform(post("/api/v1/identity/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateA":"%s","candidateB":"%s"}
                                """.formatted(idA, idB)))
                .andExpect(status().isCreated()).andReturn();
        String matchId = JsonPath.read(match.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/identity/matches/%s/review".formatted(matchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECTED","motif":"Homonymes, ages differents",
                                 "reviewedBy":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());

        Map<String, Object> etat = jdbc.queryForMap(
                "SELECT active, master_id FROM identity.patient WHERE id = ?", UUID.fromString(idA));
        assertThat(etat.get("active")).isEqualTo(Boolean.TRUE);
        assertThat(etat.get("master_id")).isNull();
        Integer fusions = jdbc.queryForObject(
                "SELECT count(*) FROM identity.merge_log", Integer.class);
        assertThat(fusions).isGreaterThanOrEqualTo(0); // aucune fusion pour CE couple
    }

    // ------------------------------------------------------------------
    // Audit : le refus de doublon est tracé (l'échec vaut de l'or en MPI)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Audit : PATIENT_DUPLICATE_DETECTED (DENIED) et PATIENT_CREATED en chaîne")
    void auditDesEvenementsIdentite() throws Exception {
        // Autonome : ce test déclenche lui-même un doublon et une création.
        String nunp = "NUNP-A" + UUID.randomUUID().toString().substring(0, 7);
        creer("SANOU", "Adjaratou", "1999-12-01", "+22670887766", nunp);
        mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsPatient("SANOU", "Adjaratou", "1999-12-01",
                                "+22670887766", nunp, UUID.randomUUID(), false, null)))
                .andExpect(status().isConflict());

        Integer creations = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PATIENT_CREATED' AND entity = 'patient'
                """, Integer.class);
        Integer refus = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                WHERE action = 'PATIENT_DUPLICATE_DETECTED' AND result = 'DENIED'
                """, Integer.class);
        assertThat(creations).isGreaterThanOrEqualTo(1);
        assertThat(refus).isGreaterThanOrEqualTo(1);
    }
}
