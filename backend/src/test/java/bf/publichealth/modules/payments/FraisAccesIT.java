package bf.publichealth.modules.payments;

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
 * Intégration I5 — le parcours monétaire RÉEL du BF : le ticket d'accès
 * se règle À LA CAISSE avant la consultation.
 *
 * <p>Couverture : ouverture idempotente du ticket du jour, encaissement
 * espèces forward-only, exonération TRACÉE (nature + motif obligatoires),
 * la porte 402 de la consultation (I5) — refusée sans ticket, refusée
 * en_attente, acceptée après paye OU exonération —, la file d'attente de
 * la caisse, et le RBAC (médecin sans paiement:initier → 403).</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "securite.jwt.actif=true",
        "securite.jwt.secret=secret-hs256-de-test-public-health-0123456789",
        "auth.demo-otp-visible=true"
})
@AutoConfigureMockMvc
class FraisAccesIT {

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

    /** Structure CSPS semée par V14. */
    private static final String CSPS = "11111111-1111-4111-8111-111111111101";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // Aides
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

    private String creerPatient(String jeton) throws Exception {
        String alea = UUID.randomUUID().toString().replace("-", "");
        var result = mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + jeton)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                        {"gender":"female","birthDate":"1992-02-20",
                                         "names":[{"use":"official","family":"%s","given":"%s"}]}
                                        """.formatted(alea.substring(0, 8).toUpperCase(),
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

    private String ouvrirTicket(String jetonCaissier, String patientId) throws Exception {
        var reponse = mockMvc.perform(post("/api/v1/frais-acces")
                        .header("Authorization", "Bearer " + jetonCaissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"structureId\":\"%s\"}"
                                .formatted(patientId, CSPS)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(reponse.getResponse().getContentAsString(), "$.id");
    }

    // ------------------------------------------------------------------
    // Le parcours monétaire dans l'ordre réel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("I5 : anonyme → 401 ; médecin sans paiement:initier → 403 à la caisse")
    void rbacCaisse() throws Exception {
        mockMvc.perform(post("/api/v1/frais-acces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"structureId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), CSPS)))
                .andExpect(status().isUnauthorized());

        String medecin = login("medecin@demo.bf");
        mockMvc.perform(post("/api/v1/frais-acces")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"structureId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), CSPS)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("I5 : PAS de ticket → consultation refusée 402, étape caisse")
    void consultationRefuseeSansTicket() throws Exception {
        String medecin = login("medecin@demo.bf");
        String patient = creerPatient(medecin);

        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Frais d'accès requis"))
                .andExpect(jsonPath("$.etape").value("caisse"));

        // Rien n'a été persisté — l'acte ne précède JAMAIS la caisse.
        Integer actes = jdbc.queryForObject(
                "SELECT count(*) FROM clinical.encounter WHERE patient_id = ?::uuid",
                Integer.class, patient);
        assertThat(actes).isZero();
    }

    @Test
    @DisplayName("I5 : ticket en_attente → consultation refusée 402 ; encaissée → consultation OK")
    void ticketEnAttentePuisEncaisse() throws Exception {
        String medecin = login("medecin@demo.bf");
        String caissier = login("caissier@demo.bf");
        String patient = creerPatient(medecin);
        String ticket = ouvrirTicket(caissier, patient);

        // Ticket ouvert mais EN ATTENTE : la salle d'attente attend la caisse.
        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value(
                        "Ticket d'accès EN ATTENTE à la caisse : encaissez ou exonérez avant la consultation"));

        // Encaissement espèces par le caissier.
        mockMvc.perform(post("/api/v1/frais-acces/%s/encaisser".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"montantXof\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("paye"))
                .andExpect(jsonPath("$.montantXof").value(1000));

        // La consultation passe — l'ordre réel est restauré.
        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.diagnosticCode").value("B54"));
    }

    @Test
    @DisplayName("I5 : exonération TRACÉE (indigent) → consultation OK sans encaissement")
    void exonererPuisConsulter() throws Exception {
        String medecin = login("medecin@demo.bf");
        String caissier = login("caissier@demo.bf");
        String patient = creerPatient(medecin);
        String ticket = ouvrirTicket(caissier, patient);

        // Nature inconnue → 400.
        mockMvc.perform(post("/api/v1/frais-acces/%s/exonerer".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nature\":\"faveur\",\"motif\":\"copain\"}"))
                .andExpect(status().isBadRequest());

        // Motif manquant → 400 (traçabilité exigée).
        mockMvc.perform(post("/api/v1/frais-acces/%s/exonerer".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nature\":\"indigent_atteste\",\"motif\":\"\"}"))
                .andExpect(status().isBadRequest());

        // Exonération valide — la gratuité est la NORME pour l'indigent attesté.
        mockMvc.perform(post("/api/v1/frais-acces/%s/exonerer".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nature":"indigent_atteste",
                                 "motif":"Attestation N0123-2025 du maire de Saaba"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statut").value("exonere"))
                .andExpect(jsonPath("$.exonerationNature").value("indigent_atteste"));

        mockMvc.perform(post("/api/v1/consultations")
                        .header("Authorization", "Bearer " + medecin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpsConsultation(patient)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("I5 : forward-only — re-encaissement 409, re-exonération 409, rejeu = même ticket")
    void machineForwardOnlyEtIdempotence() throws Exception {
        String medecin = login("medecin@demo.bf");
        String caissier = login("caissier@demo.bf");
        String patient = creerPatient(medecin);
        String ticket = ouvrirTicket(caissier, patient);

        // Rejeu du jour : 200, MÊME ticket (idempotence du parcours).
        var rejeu = mockMvc.perform(post("/api/v1/frais-acces")
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\",\"structureId\":\"%s\"}"
                                .formatted(patient, CSPS)))
                .andExpect(status().isOk())
                .andReturn();
        String ticketRejoue = JsonPath.read(rejeu.getResponse().getContentAsString(), "$.id");
        assertThat(ticketRejoue).isEqualTo(ticket);

        // Encaissement puis re-encaissement → 409 (paye est TERMINAL).
        mockMvc.perform(post("/api/v1/frais-acces/%s/encaisser".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/frais-acces/%s/encaisser".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Ticket déjà réglé (paye) : en_attente → paye est forward-only"));

        // Re-exonération → 409 aussi.
        mockMvc.perform(post("/api/v1/frais-acces/%s/exonerer".formatted(ticket))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nature\":\"cesarienne\",\"motif\":\"re-test\"}"))
                .andExpect(status().isConflict());

        // Un seul ticket par patient × structure × jour (défense de base).
        Integer tickets = jdbc.queryForObject("""
                SELECT count(*) FROM payments.frais_acces
                 WHERE patient_id = ?::uuid AND structure_id = ?::uuid
                """, Integer.class, patient, CSPS);
        assertThat(tickets).isEqualTo(1);
    }

    @Test
    @DisplayName("I5 : file d'attente caisse — tickets en_attente du jour, FIFO")
    void fileAttenteCaisse() throws Exception {
        String medecin = login("medecin@demo.bf");
        String caissier = login("caissier@demo.bf");
        String patient = creerPatient(medecin);
        String ticket = ouvrirTicket(caissier, patient);

        var reponse = mockMvc.perform(get("/api/v1/frais-acces?structureId=%s&statut=en_attente"
                        .formatted(CSPS))
                        .header("Authorization", "Bearer " + caissier))
                .andExpect(status().isOk())
                .andReturn();
        java.util.List<?> file = JsonPath.read(
                reponse.getResponse().getContentAsString(), "$[?(@.id=='%s')]".formatted(ticket));
        assertThat(file).hasSize(1);

        // Le superviseur (paiement:lire) voit la file, la lecture est autorisée.
        mockMvc.perform(get("/api/v1/frais-acces?structureId=%s&statut=en_attente"
                        .formatted(CSPS))
                        .header("Authorization", "Bearer " + login("superviseur@demo.bf")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("I5 : ticket introuvable → 404 ; audit de la décision de caisse")
    void ticketIntrouvableEtAudit() throws Exception {
        String caissier = login("caissier@demo.bf");
        mockMvc.perform(post("/api/v1/frais-acces/%s/encaisser".formatted(UUID.randomUUID()))
                        .header("Authorization", "Bearer " + caissier)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        // La décision de caisse est auditée avec l'acteur du jeton (V14).
        Integer audits = jdbc.queryForObject("""
                SELECT count(*) FROM audit.entry
                 WHERE action = 'FRAIS_ACCES_ENCAISSE'
                """, Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }
}
