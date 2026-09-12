package bf.publichealth.modules.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

import org.hl7.fhir.instance.model.api.IBaseResource;

import bf.publichealth.common.UuidV7;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — épique E7 : la façade FHIR R4 lecture/recherche.
 *
 * <p>La donnée est SEEDÉE par les contrôleurs API existants (POST /api/v1/
 * patients, POST /api/v1/sync, POST /api/v1/prescriptions) PUIS lue par la
 * façade — la preuve que la façade interopère sur le vrai modèle de stockage.
 * Chaque ressource rendue est RELUE par le validateur HAPI R4 (même montage
 * que FhirValidationTest) : zéro erreur attendue, warnings best-practice
 * (narratif dom-6) tolérés car non bloquants.</p>
 *
 * <p>Posture sécurité : /fhir/** est refusé par le SecurityConfig Sprint 0
 * (anyRequest().denyAll) — la posture E5 est simulée ici par une chaîne de
 * test ordonnée AVANT la chaîne principale (matcher /fhir/**), le temps que
 * SecurityConfig intègre la façade. Voir rapport : matcher exact à ajouter.</p>
 */
@Tag("integration")
@SpringBootTest(properties = "fedapay.webhook-secret=secret-de-test")
@AutoConfigureMockMvc
class FhirFacadeIT {

    private static final String FHIR_JSON = "application/fhir+json";

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

    /**
     * Posture E5 simulée (TEST UNIQUEMENT — ne touche pas SecurityConfig) :
     * une chaîne ordonnée avant la chaîne principale qui ouvre /fhir/** en
     * lecture. En production, la posture réelle (JWT + RBAC) remplacera
     * ceci : à intégrer dans SecurityConfig à l'épique E5.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PostureFhirPourLesTests {

        @Bean
        @Order(1)
        SecurityFilterChain chaineFhirOuverte(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/fhir/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FhirContext contexte;

    // ------------------------------------------------------------------
    // Validateur HAPI — même montage que FhirValidationTest
    // ------------------------------------------------------------------

    private FhirValidator validateur() {
        FhirValidator validateur = contexte.newValidator();
        validateur.setValidateAgainstStandardSchema(false);
        validateur.setValidateAgainstStandardSchematron(false);
        validateur.registerValidatorModule(
                new org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator(contexte));
        return validateur;
    }

    /** Zéro erreur, zéro message de sévérité ERROR/FATAL (warnings BP tolérés). */
    private void conformeR4(String json) {
        IBaseResource ressource = contexte.newJsonParser().parseResource(json);
        ValidationResult resultat = validateur().validateWithResult(ressource);
        assertThat(resultat.isSuccessful())
                .as("Validation R4 : %s", resultat.getMessages())
                .isTrue();
        assertThat(resultat.getMessages())
                .as("Aucun message de sévérité ERROR/FATAL : %s", resultat.getMessages())
                .noneMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                        || m.getSeverity() == ResultSeverityEnum.FATAL);
    }

    /** Valide le Bundle ET chaque ressource embarquée dans ses entrées. */
    private Bundle bundleConforme(String json) {
        conformeR4(json);
        Bundle bundle = (Bundle) contexte.newJsonParser().parseResource(json);
        for (BundleEntryComponent entree : bundle.getEntry()) {
            conformeR4(contexte.newJsonParser().encodeResourceToString(entree.getResource()));
        }
        return bundle;
    }

    private OperationOutcome outcomeConforme(String json) {
        conformeR4(json);
        return (OperationOutcome) contexte.newJsonParser().parseResource(json);
    }

    private String corps(ResultActions resultat) throws Exception {
        return resultat.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Seed via les API existantes (jamais via SQL direct)
    // ------------------------------------------------------------------

    private String creerPatient(String family, String given, String birthDate,
                                String telephone, String nunp) throws Exception {
        String telJson = telephone == null ? ""
                : "\"telecoms\":[{\"system\":\"phone\",\"value\":\"%s\",\"use\":\"mobile\"}],"
                        .formatted(telephone);
        String idJson = nunp == null ? ""
                : "\"identifiers\":[{\"system\":\"NUNP\",\"value\":\"%s\"}],"
                        .formatted(nunp);
        var resultat = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"gender":"female","birthDate":"%s",
                                 "names":[{"use":"official","family":"%s","given":"%s"}],
                                 %s%s"createdBy":"%s"}
                                """.formatted(birthDate, family, given, telJson, idJson,
                                UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phReference").isNotEmpty())
                .andReturn();
        return JsonPath.read(resultat.getResponse().getContentAsString(), "$.id");
    }

    /** Patient avec forceCreate (plusieurs dossiers de même patronyme). */
    private String creerPatientForce(String family, String given, String birthDate) throws Exception {
        var resultat = mockMvc.perform(post("/api/v1/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"forceCreate":true,"gender":"male","birthDate":"%s",
                                 "names":[{"use":"official","family":"%s","given":"%s"}],
                                 "createdBy":"%s"}
                                """.formatted(birthDate, family, given, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(resultat.getResponse().getContentAsString(), "$.id");
    }

    private String declarerAppareil() throws Exception {
        var resultat = mockMvc.perform(post("/api/v1/sync/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"deviceName\":\"IT-FHIR-%s\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(resultat.getResponse().getContentAsString(), "$.deviceId");
    }

    private String uplink(String deviceId, String... ops) throws Exception {
        return mockMvc.perform(post("/api/v1/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\",\"ops\":[%s]}"
                                .formatted(deviceId, String.join(",", ops))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String opEncounter(UUID encounterId, UUID patientId, String classe,
                               String startedAt, String endedAt) {
        return """
                {"opId":"%s","entity":"encounter","entityId":"%s","payload":{
                  "patientId":"%s","facilityId":"%s","encounterClass":"%s",
                  "reason":"Consultation generale","startedAt":"%s"%s}}
                """.formatted(UuidV7.next(), encounterId, patientId, UUID.randomUUID(), classe,
                startedAt, endedAt == null ? "" : ",\"endedAt\":\"%s\"".formatted(endedAt));
    }

    private String opObservation(UUID observationId, UUID encounterId, UUID patientId,
                                 String code, String valueNum, String valueText, String statut) {
        String valeur = valueNum != null ? "\"valueNum\":%s".formatted(valueNum)
                : "\"valueText\":\"%s\"".formatted(valueText);
        return """
                {"opId":"%s","entity":"observation","entityId":"%s","payload":{
                  "encounterId":"%s","patientId":"%s","code":"%s",%s%s,
                  "effectiveAt":"2025-06-01T09:00:00Z"}}
                """.formatted(UuidV7.next(), observationId, encounterId, patientId, code, valeur,
                statut == null ? "" : ",\"status\":\"%s\"".formatted(statut));
    }

    private String opCondition(UUID conditionId, UUID encounterId, UUID patientId,
                               String code, String clinicalStatus) {
        return """
                {"opId":"%s","entity":"condition","entityId":"%s","payload":{
                  "encounterId":"%s","patientId":"%s","code":"%s","clinicalStatus":"%s"}}
                """.formatted(UuidV7.next(), conditionId, encounterId, patientId, code,
                clinicalStatus);
    }

    private String creerPrescription(String patientId, String quantite) throws Exception {
        var resultat = mockMvc.perform(post("/api/v1/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId":"%s","prescriberId":"%s","items":[{
                                  "medicationCode":"PARA-500","medicationLabel":"Paracétamol 500 mg",
                                  "dose":"1 comprimé","form":"comprimé","route":"oral",
                                  "frequency":"3 fois par jour","durationDays":5,
                                  "quantityPrescribed":%s}]}
                                """.formatted(patientId, UUID.randomUUID(), quantite)))
                .andExpect(status().isCreated())
                .andReturn();
        return resultat.getResponse().getContentAsString();
    }

    private String premiereLigne(String corpsPrescription) {
        return JsonPath.read(corpsPrescription, "$.items[0].id");
    }

    // ------------------------------------------------------------------
    // metadata — CapabilityStatement conforme et honnête
    // ------------------------------------------------------------------

    @Test
    @DisplayName("metadata : CapabilityStatement valide, fhirVersion 4.0.1, lecture seule, routes réelles")
    void metadataConformeEtHonnête() throws Exception {
        String corps = corps(mockMvc.perform(get("/fhir/R4/metadata"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));

        conformeR4(corps);
        CapabilityStatement capacite = (CapabilityStatement) contexte.newJsonParser()
                .parseResource(corps);
        assertThat(capacite.getFhirVersion().toCode()).isEqualTo("4.0.1");
        assertThat(capacite.getStatus().toCode()).isEqualTo("active");
        assertThat(capacite.getKind().toCode()).isEqualTo("instance");
        assertThat(capacite.getFormat().stream()
                .map(org.hl7.fhir.r4.model.CodeType::getValue).toList())
                .containsExactly("json");
        assertThat(capacite.getSoftware().getName()).isEqualTo("PUBLIC HEALTH");
        assertThat(capacite.getDate()).isNotNull();

        assertThat(capacite.getRest()).hasSize(1);
        assertThat(capacite.getRestFirstRep().getMode().toCode()).isEqualTo("server");
        Map<String, Set<String>> interactions = new HashMap<>();
        Map<String, Set<String>> parametres = new HashMap<>();
        capacite.getRestFirstRep().getResource().forEach(r -> {
            interactions.put(r.getType(), r.getInteraction().stream()
                    .map(i -> i.getCode().toCode()).collect(Collectors.toSet()));
            parametres.put(r.getType(), r.getSearchParam().stream()
                    .map(CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent::getName)
                    .collect(Collectors.toSet()));
        });
        // Lecture seule, partout : read + search-type, RIEN d'autre.
        assertThat(interactions).allSatisfy((type, codes) ->
                assertThat(codes).as("interactions %s", type).containsExactlyInAnyOrder("read", "search-type"));
        assertThat(interactions.keySet()).containsExactlyInAnyOrder(
                "Patient", "Encounter", "Observation", "Condition", "MedicationRequest");
        assertThat(parametres.get("Patient"))
                .containsExactlyInAnyOrder("family", "given", "birthdate", "phone", "identifier");
        assertThat(parametres.get("Encounter")).containsExactly("patient");
        assertThat(parametres.get("Observation")).containsExactlyInAnyOrder("patient", "encounter");
        assertThat(parametres.get("Condition")).containsExactly("patient");
        assertThat(parametres.get("MedicationRequest")).containsExactlyInAnyOrder("patient", "status");
    }

    // ------------------------------------------------------------------
    // Patient — read + mapping identifiants/noms/télécoms
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Patient read : 200 fhir+json, mapping PH/NUNP/CNIB, nom, téléphone, naissance, meta")
    void lecturePatientEtMapping() throws Exception {
        String nunp = "NUNP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String tel = "+22670" + UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient("FACADEREAD", "Aminata", "1994-06-15", tel, nunp);
        String phReference = JsonPath.read(mockMvc
                .perform(get("/api/v1/patients/" + patientId)).andReturn()
                .getResponse().getContentAsString(), "$.phReference");

        String corps = corps(mockMvc.perform(get("/fhir/R4/Patient/{id}", patientId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON))
                .andExpect(jsonPath("$.resourceType").value("Patient")));

        conformeR4(corps);
        Patient patient = (Patient) contexte.newJsonParser().parseResource(corps);
        assertThat(patient.getIdElement().getIdPart()).isEqualTo(patientId);

        Map<String, String> identifiants = patient.getIdentifier().stream()
                .collect(Collectors.toMap(Identifier::getSystem, Identifier::getValue, (a, b) -> a));
        assertThat(identifiants)
                .containsEntry("https://publichealth.bf/id/ph", phReference)
                .containsEntry("https://publichealth.bf/id/nunp", nunp);
        assertThat(patient.getName()).hasSize(1);
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("FACADEREAD");
        assertThat(patient.getNameFirstRep().getUse().toCode()).isEqualTo("official");
        assertThat(patient.getNameFirstRep().getGiven())
                .extracting(StringType::getValue).containsExactly("Aminata");
        assertThat(patient.getTelecomFirstRep().getSystem().toCode()).isEqualTo("phone");
        assertThat(patient.getTelecomFirstRep().getUse().toCode()).isEqualTo("mobile");
        assertThat(patient.getTelecomFirstRep().getValue()).isEqualTo(tel);
        assertThat(patient.getGender().toCode()).isEqualTo("female");
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1994-06-15");
        assertThat(patient.getActive()).isTrue();
        assertThat(patient.getMeta().getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("Patient inconnu : 404 OperationOutcome not-found, valide R4")
    void patientInconnu404() throws Exception {
        String corps = corps(mockMvc.perform(get("/fhir/R4/Patient/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        OperationOutcome outcome = outcomeConforme(corps);
        assertThat(outcome.getIssueFirstRep().getSeverity().toCode()).isEqualTo("error");
        assertThat(outcome.getIssueFirstRep().getCode().toCode()).isEqualTo("not-found");
        assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("Patient/");
    }

    @Test
    @DisplayName("Patient fusionné : 410 OperationOutcome + extension master-id vers le maître")
    void patientFusionne410() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String idA = creerPatient("FUSIONA" + suffixe, "Blaise", "1990-07-14",
                "+22671" + UUID.randomUUID().toString().substring(0, 6), null);
        String idB = creerPatient("FUSIONB" + suffixe, "Fati", "1985-03-09", null, null);

        var match = mockMvc.perform(post("/api/v1/identity/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateA\":\"%s\",\"candidateB\":\"%s\"}"
                                .formatted(idA, idB)))
                .andExpect(status().isCreated()).andReturn();
        String matchId = JsonPath.read(match.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/identity/matches/{id}/review", matchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ACCEPTED","masterId":"%s",
                                 "motif":"Double enregistrement confirme par l'agent",
                                 "reviewedBy":"%s"}
                                """.formatted(idB, UUID.randomUUID())))
                .andExpect(status().isOk());

        String corps = corps(mockMvc.perform(get("/fhir/R4/Patient/{id}", idA))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        OperationOutcome outcome = outcomeConforme(corps);
        assertThat(outcome.getIssueFirstRep().getCode().toCode()).isEqualTo("deleted");
        assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("fusionn");

        // L'extension master-id pointe le dossier maître, en référence.
        var extension = outcome.getIssueFirstRep().getExtensionByUrl(
                "https://publichealth.bf/fhir/StructureDefinition/master-id");
        assertThat(extension).isNotNull();
        assertThat(extension.getValue()).isInstanceOf(Reference.class);
        assertThat(((Reference) extension.getValue()).getReference()).isEqualTo("Patient/" + idB);

        // Le maître, lui, se lit normalement.
        mockMvc.perform(get("/fhir/R4/Patient/{id}", idB))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));
    }

    // ------------------------------------------------------------------
    // Patient — recherche
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Recherche Patient : patronyme partiel, naissance exacte, téléphone, vide = Bundle vide")
    void recherchePatientParPatronymeNaissanceTelephone() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String famille = "FACASRC" + suffixe;
        String tel = "+22676" + UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient(famille, "Issa Karim", "2001-09-30", tel, null);

        ResultActions recherche = mockMvc.perform(get("/fhir/R4/Patient")
                        .param("family", famille.substring(0, 7))) // préfixe PARTIEL
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON))
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset"))
                .andExpect(jsonPath("$.total").value(1));
        Bundle bundle = bundleConforme(corps(recherche));
        assertThat(bundle.getEntry()).hasSize(1);
        assertThat(bundle.getEntryFirstRep().getFullUrl())
                .isEqualTo("http://localhost/fhir/R4/Patient/" + patientId);
        assertThat(bundle.getLink("self").getUrl()).startsWith("http://localhost/fhir/R4/Patient?");
        assertThat(bundle.getLink("next")).isNull();

        // birthdate exacte (combinée au patronyme)
        mockMvc.perform(get("/fhir/R4/Patient")
                        .param("family", famille).param("birthdate", "2001-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.id").value(patientId));

        mockMvc.perform(get("/fhir/R4/Patient")
                        .param("family", famille).param("birthdate", "2001-09-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // téléphone exact
        mockMvc.perform(get("/fhir/R4/Patient").param("phone", tel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.id").value(patientId));

        // Résultat vide : un Bundle VIDE, jamais une erreur.
        String vide = corps(mockMvc.perform(get("/fhir/R4/Patient")
                        .param("family", "NEXISTEPAS" + suffixe))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        Bundle bundleVide = bundleConforme(vide);
        assertThat(bundleVide.getTotal()).isEqualTo(0);
        assertThat(bundleVide.getEntry()).isEmpty();
    }

    @Test
    @DisplayName("Recherche Patient par identifier : valeur seule, system|value, system inconnu = vide")
    void recherchePatientParIdentifiant() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String nunp = "NUNP-" + suffixe.toUpperCase();
        String patientId = creerPatient("FACAIDENT" + suffixe, "Rasmane", "1979-11-03",
                "+22678" + UUID.randomUUID().toString().substring(0, 6), nunp);
        String phReference = JsonPath.read(mockMvc
                .perform(get("/api/v1/patients/" + patientId)).andReturn()
                .getResponse().getContentAsString(), "$.phReference");

        // Valeur seule : la ph_reference matche.
        mockMvc.perform(get("/fhir/R4/Patient").param("identifier", phReference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.id").value(patientId));

        // system|value complet.
        mockMvc.perform(get("/fhir/R4/Patient")
                        .param("identifier", "https://publichealth.bf/id/nunp|" + nunp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.id").value(patientId));

        // System inconnu : sémantique token — rien ne peut matcher, PAS d'erreur.
        mockMvc.perform(get("/fhir/R4/Patient")
                        .param("identifier", "urn:inconnu|whatever"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("Pagination Patient : _count respecté, total juste, lien next suivi jusqu'au bout")
    void paginationPatientCountEtNext() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String famille = "FACAPAGI" + suffixe;
        creerPatientForce(famille, "Alpha", "1960-01-01");
        creerPatientForce(famille, "Beta", "1961-01-01");
        creerPatientForce(famille, "Gamma", "1962-01-01");

        String page1 = corps(mockMvc.perform(get("/fhir/R4/Patient")
                        .param("family", famille).param("_count", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        Bundle bundle1 = bundleConforme(page1);
        assertThat(bundle1.getTotal()).isEqualTo(3);
        assertThat(bundle1.getEntry()).hasSize(2);
        assertThat(bundle1.getLink("self").getUrl()).contains("family=")
                .contains("_count=2").contains("page%5Boffset%5D=0");
        assertThat(bundle1.getLink("next")).isNotNull();
        assertThat(bundle1.getLink("next").getUrl()).contains("page%5Boffset%5D=2");

        // On suit le lien next : un vrai conteneur servlet DÉCODE %5B/%5D dans
        // le nom du paramètre (page[offset]) ; MockMvc sur URL brute ne le
        // fait pas — on décode donc explicitement, comme un client réel.
        URI suivant = URI.create(bundle1.getLink("next").getUrl());
        String requeteSuivante = java.net.URLDecoder.decode(suivant.getRawQuery(), StandardCharsets.UTF_8);
        String page2 = corps(mockMvc.perform(get(suivant.getPath() + "?" + requeteSuivante))
                .andExpect(status().isOk()));
        Bundle bundle2 = bundleConforme(page2);
        assertThat(bundle2.getTotal()).isEqualTo(3);
        assertThat(bundle2.getEntry()).hasSize(1);
        assertThat(bundle2.getLink("next")).isNull(); // fin de pagination

        // Les pages ne se recouvrent pas.
        List<String> idsPage1 = bundle1.getEntry().stream()
                .map(e -> e.getResource().getIdElement().getIdPart()).toList();
        List<String> idsPage2 = bundle2.getEntry().stream()
                .map(e -> e.getResource().getIdElement().getIdPart()).toList();
        assertThat(idsPage1).doesNotContainAnyElementsOf(idsPage2);
    }

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Encounter : recherche par patient, statuts dérivés (finished/in-progress/planned), read, 404, 400 sans patient")
    void encounterRechercheEtStatutsDerives() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient("FACAENC" + suffixe, "Mariam", "1992-02-20",
                "+22670" + UUID.randomUUID().toString().substring(0, 6), null);
        String deviceId = declarerAppareil();
        UUID termine = UuidV7.next();
        UUID enCours = UuidV7.next();
        UUID planifie = UuidV7.next();
        uplink(deviceId,
                opEncounter(termine, UUID.fromString(patientId), "consultation",
                        "2025-03-01T08:00:00Z", "2025-03-01T10:00:00Z"),
                opEncounter(enCours, UUID.fromString(patientId), "urgence",
                        "2025-06-01T08:00:00Z", null),
                opEncounter(planifie, UUID.fromString(patientId), "hospitalisation",
                        "2099-06-01T08:00:00Z", null));

        String corps = corps(mockMvc.perform(get("/fhir/R4/Encounter")
                        .param("patient", patientId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON))
                .andExpect(jsonPath("$.total").value(3)));
        Bundle bundle = bundleConforme(corps);

        Map<String, String> statuts = new HashMap<>();
        bundle.getEntry().forEach(e -> statuts.put(e.getResource().getIdElement().getIdPart(),
                ((org.hl7.fhir.r4.model.Encounter) e.getResource()).getStatus().toCode()));
        assertThat(statuts).containsEntry(termine.toString(), "finished");
        assertThat(statuts).containsEntry(enCours.toString(), "in-progress");
        assertThat(statuts).containsEntry(planifie.toString(), "planned");

        org.hl7.fhir.r4.model.Encounter lu = (org.hl7.fhir.r4.model.Encounter)
                contexte.newJsonParser().parseResource(corps(mockMvc
                        .perform(get("/fhir/R4/Encounter/{id}", termine))
                        .andExpect(status().isOk())));
        conformeR4(contexte.newJsonParser().encodeResourceToString(lu));
        assertThat(lu.getStatus().toCode()).isEqualTo("finished");
        assertThat(lu.getClass_().getSystem())
                .isEqualTo("http://terminology.hl7.org/CodeSystem/v3-ActCode");
        assertThat(lu.getClass_().getCode()).isEqualTo("AMB"); // consultation → AMB
        assertThat(lu.getSubject().getReference()).isEqualTo("Patient/" + patientId);
        assertThat(lu.getPeriod().getStart()).isNotNull();
        assertThat(lu.getPeriod().getEnd()).isNotNull();

        mockMvc.perform(get("/fhir/R4/Encounter/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));

        String sansPatient = corps(mockMvc.perform(get("/fhir/R4/Encounter"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(sansPatient).getIssueFirstRep().getCode().toCode())
                .isEqualTo("invalid");
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Observation : recherche par patient ET par encounter, valueQuantity/valueString, entered-in-error")
    void observationParPatientEtParEncounter() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient("FACAOBS" + suffixe, "Adjaratou", "1999-12-01",
                "+22672" + UUID.randomUUID().toString().substring(0, 6), null);
        String deviceId = declarerAppareil();
        UUID rencontre1 = UuidV7.next();
        UUID rencontre2 = UuidV7.next();
        UUID obsNumerique = UuidV7.next();
        UUID obsTexte = UuidV7.next();
        UUID obsAnnulee = UuidV7.next();
        UUID obsAutre = UuidV7.next();
        uplink(deviceId,
                opEncounter(rencontre1, UUID.fromString(patientId), "consultation",
                        "2025-06-01T08:00:00Z", null),
                opEncounter(rencontre2, UUID.fromString(patientId), "consultation",
                        "2025-06-02T08:00:00Z", null),
                opObservation(obsNumerique, rencontre1, UUID.fromString(patientId),
                        "TEMP_ER", "38.9", null, null),
                opObservation(obsTexte, rencontre1, UUID.fromString(patientId),
                        "SYMPT", null, "Toux seche", null),
                opObservation(obsAnnulee, rencontre1, UUID.fromString(patientId),
                        "POIDS", "70", null, "entered-in-error"),
                opObservation(obsAutre, rencontre2, UUID.fromString(patientId),
                        "TA", "12.8", null, null));

        String parPatient = corps(mockMvc.perform(get("/fhir/R4/Observation")
                        .param("patient", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4)));
        bundleConforme(parPatient);

        String parEncounter = corps(mockMvc.perform(get("/fhir/R4/Observation")
                        .param("encounter", rencontre1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)));
        bundleConforme(parEncounter);

        // Les deux critères combinés (sémantique ET).
        mockMvc.perform(get("/fhir/R4/Observation")
                        .param("patient", patientId).param("encounter", rencontre2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // Read : valueQuantity sans unité inventée, code system local, statut.
        org.hl7.fhir.r4.model.Observation lue = (org.hl7.fhir.r4.model.Observation)
                contexte.newJsonParser().parseResource(corps(mockMvc
                        .perform(get("/fhir/R4/Observation/{id}", obsNumerique))
                        .andExpect(status().isOk())));
        conformeR4(contexte.newJsonParser().encodeResourceToString(lue));
        assertThat(lue.getStatus().toCode()).isEqualTo("final");
        assertThat(lue.getCode().getCodingFirstRep().getSystem())
                .isEqualTo("https://publichealth.bf/fhir/CodeSystem/observation-code");
        assertThat(lue.getCode().getCodingFirstRep().getCode()).isEqualTo("TEMP_ER");
        assertThat(lue.getValueQuantity().getValue().doubleValue()).isEqualTo(38.9);
        assertThat(lue.getValueQuantity().getUnit()).isNull();
        assertThat(lue.getEffectiveDateTimeType().getValueAsString())
                .startsWith("2025-06-01T09:00:00");
        assertThat(lue.getSubject().getReference()).isEqualTo("Patient/" + patientId);

        org.hl7.fhir.r4.model.Observation annulee = (org.hl7.fhir.r4.model.Observation)
                contexte.newJsonParser().parseResource(corps(mockMvc
                        .perform(get("/fhir/R4/Observation/{id}", obsAnnulee))
                        .andExpect(status().isOk())));
        assertThat(annulee.getStatus().toCode()).isEqualTo("entered-in-error");

        mockMvc.perform(get("/fhir/R4/Observation"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Condition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Condition : recherche par patient, clinicalStatus actif mappé, code texte, read, 404")
    void conditionParPatientEtLecture() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient("FACACOND" + suffixe, "Pascal", "1975-05-05",
                "+22674" + UUID.randomUUID().toString().substring(0, 6), null);
        String deviceId = declarerAppareil();
        UUID rencontre = UuidV7.next();
        UUID probleme = UuidV7.next();
        uplink(deviceId,
                opEncounter(rencontre, UUID.fromString(patientId), "consultation",
                        "2025-05-01T08:00:00Z", null),
                opCondition(probleme, rencontre, UUID.fromString(patientId),
                        "MALARIA_SUSPECT", "active"));

        String corps = corps(mockMvc.perform(get("/fhir/R4/Condition")
                        .param("patient", patientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)));
        Bundle bundle = bundleConforme(corps);
        org.hl7.fhir.r4.model.Condition lue = (org.hl7.fhir.r4.model.Condition)
                bundle.getEntryFirstRep().getResource();
        assertThat(lue.getClinicalStatus().getCodingFirstRep().getSystem())
                .isEqualTo("http://terminology.hl7.org/CodeSystem/condition-clinical");
        assertThat(lue.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(lue.getCode().getText()).isEqualTo("MALARIA_SUSPECT");
        assertThat(lue.getSubject().getReference()).isEqualTo("Patient/" + patientId);
        assertThat(lue.getRecordedDate()).isNotNull();

        mockMvc.perform(get("/fhir/R4/Condition/{id}", probleme))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));

        String quatreCentQuatre = corps(mockMvc.perform(get("/fhir/R4/Condition/{id}",
                        UUID.randomUUID()))
                .andExpect(status().isNotFound()));
        assertThat(outcomeConforme(quatreCentQuatre).getIssueFirstRep().getCode().toCode())
                .isEqualTo("not-found");
    }

    // ------------------------------------------------------------------
    // MedicationRequest (prescriptions E3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MedicationRequest : statuts E3 dérivés (completed/active/cancelled), filtre status, read par ligne")
    void medicationRequestRefleteLaPrescriptionE3() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 6);
        String patientId = creerPatient("FACAMED" + suffixe, "Boureima", "1968-01-25",
                "+22675" + UUID.randomUUID().toString().substring(0, 6), null);

        // 1) Prescription intégralement dispensée → completed.
        String prescriptionEpuisee = creerPrescription(patientId, "10.00");
        String ligneEpuisee = premiereLigne(prescriptionEpuisee);
        mockMvc.perform(post("/api/v1/prescriptions/{id}/items/{itemId}/dispense",
                        prescriptionId(prescriptionEpuisee), ligneEpuisee)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 10.00, \"clientRequestId\": \"%s\", \"dispensedBy\": \"%s\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isCreated());

        // 2) Prescription active non dispensée → active.
        String prescriptionActive = creerPrescription(patientId, "15.00");

        // 3) Prescription annulée → cancelled.
        String prescriptionAnnulee = creerPrescription(patientId, "6.00");
        mockMvc.perform(post("/api/v1/prescriptions/{id}/cancel",
                        prescriptionId(prescriptionAnnulee))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Sortie anticipee du patient\"}"))
                .andExpect(status().isOk());

        String corps = corps(mockMvc.perform(get("/fhir/R4/MedicationRequest")
                        .param("patient", patientId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON))
                .andExpect(jsonPath("$.total").value(3)));
        Bundle bundle = bundleConforme(corps);

        Map<String, String> statuts = new HashMap<>();
        bundle.getEntry().forEach(e -> {
            MedicationRequest mr = (MedicationRequest) e.getResource();
            statuts.put(mr.getIdElement().getIdPart(), mr.getStatus().toCode());
        });
        assertThat(statuts).containsEntry(ligneEpuisee, "completed");
        assertThat(statuts.values()).containsExactlyInAnyOrder("completed", "active", "cancelled");

        MedicationRequest ligne = (MedicationRequest) bundle.getEntry().stream()
                .filter(e -> ligneEpuisee.equals(e.getResource().getIdElement().getIdPart()))
                .findFirst().orElseThrow().getResource();
        assertThat(ligne.getIntent().toCode()).isEqualTo("order");
        assertThat(ligne.getMedicationCodeableConcept().getCodingFirstRep().getSystem())
                .isEqualTo("https://publichealth.bf/fhir/CodeSystem/medication");
        assertThat(ligne.getMedicationCodeableConcept().getCodingFirstRep().getCode())
                .isEqualTo("PARA-500");
        assertThat(ligne.getMedicationCodeableConcept().getCodingFirstRep().getDisplay())
                .isEqualTo("Paracétamol 500 mg");
        assertThat(ligne.getSubject().getReference()).isEqualTo("Patient/" + patientId);
        assertThat(ligne.getAuthoredOnElement().getValueAsString()).isNotBlank();
        assertThat(ligne.getDosageInstructionFirstRep().getText())
                .contains("comprimé").contains("5");
        assertThat(ligne.getDosageInstructionFirstRep().getTiming()
                .getRepeat().getDuration().intValue()).isEqualTo(5);

        // Filtre par statut FHIR (dérivé).
        mockMvc.perform(get("/fhir/R4/MedicationRequest")
                        .param("patient", patientId).param("status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.status").value("completed"));
        mockMvc.perform(get("/fhir/R4/MedicationRequest")
                        .param("patient", patientId).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.entry[0].resource.status").value("active"));
        mockMvc.perform(get("/fhir/R4/MedicationRequest")
                        .param("patient", patientId).param("status", "on-hold"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));

        // Read par identifiant de LIGNE.
        mockMvc.perform(get("/fhir/R4/MedicationRequest/{id}", ligneEpuisee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.resourceType").value("MedicationRequest"));

        mockMvc.perform(get("/fhir/R4/MedicationRequest/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String prescriptionId(String corpsPrescription) {
        return JsonPath.read(corpsPrescription, "$.id");
    }

    // ------------------------------------------------------------------
    // _format, erreurs de paramètres, lecture seule
    // ------------------------------------------------------------------

    @Test
    @DisplayName("_format=json accepté, _format=xml refusé (400 not-supported), paramètres absurdes en 400 invalid")
    void formatEtParametresInvalides() throws Exception {
        mockMvc.perform(get("/fhir/R4/metadata").param("_format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));

        String xml = corps(mockMvc.perform(get("/fhir/R4/metadata").param("_format", "xml"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(xml).getIssueFirstRep().getCode().toCode())
                .isEqualTo("not-supported");

        // Accept incompatible (la façade ne produit que application/fhir+json) :
        // 406 OperationOutcome, pas un 500 problem+json.
        String inacceptable = corps(mockMvc.perform(get("/fhir/R4/metadata")
                        .accept(MediaType.parseMediaType("application/xml")))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(inacceptable).getIssueFirstRep().getCode().toCode())
                .isEqualTo("not-supported");

        mockMvc.perform(get("/fhir/R4/Patient").param("_count", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/R4/Patient").param("_count", "abc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/R4/Patient").param("page[offset]", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/R4/Patient").param("birthdate", "pas-une-date"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/fhir/R4/Patient").param("identifier", "ph|"))
                .andExpect(status().isBadRequest());

        // Identifiant de chemin non uuid → 400 invalid OperationOutcome.
        String chemin = corps(mockMvc.perform(get("/fhir/R4/Patient/{id}", "pas-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(chemin).getIssueFirstRep().getCode().toCode())
                .isEqualTo("invalid");

        // _count > 200 → plafonné silencieusement à 200 (pas d'erreur).
        mockMvc.perform(get("/fhir/R4/Patient").param("_count", "500"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Aucune écriture possible : POST/PUT/DELETE sur /fhir/** → 405 OperationOutcome not-supported")
    void lectureSeuleAucuneRouteDEcriture() throws Exception {
        // POST : interaction non supportée (levée avant tout contrôleur —
        // FhirErreurResolver la convertit en OperationOutcome, pas en 500).
        String corps = corps(mockMvc.perform(post("/fhir/R4/Patient")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(corps).getIssueFirstRep().getCode().toCode())
                .isEqualTo("not-supported");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/fhir/R4/Patient/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/fhir/R4/MedicationRequest/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());

        // Route inconnue sous /fhir/ : 404 OperationOutcome (pas un 500 problem+json).
        String inconnue = corps(mockMvc.perform(get("/fhir/R4/DiagnosticReport")
                        .param("patient", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(FHIR_JSON)));
        assertThat(outcomeConforme(inconnue).getIssueFirstRep().getCode().toCode())
                .isEqualTo("not-found");
    }

    // ------------------------------------------------------------------
    // Amorçage PostgreSQL (Testcontainers en CI, zonky en local)
    // ------------------------------------------------------------------

    private static boolean dockerDisponible() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }
}
