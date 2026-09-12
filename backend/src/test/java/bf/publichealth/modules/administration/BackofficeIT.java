package bf.publichealth.modules.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

import bf.publichealth.modules.administration.domain.RolesPermissions;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Intégration E2E — épique E6 : back-office minimal.
 *
 * <p>Couverture : CRUD structure (201 + Location, 409 mnémonique,
 * PATCH nom/localisation, 404), recherche (région, type, texte,
 * active) et arborescence par région, désactivation/réactivation
 * SOFT (409 sur double), invitation utilisateur (409 doublon email,
 * même casse différente ; structure inconnue), activation avec
 * liaison Supabase (409 double liaison — le même compte Supabase sur
 * un autre utilisateur), suspension + réactivation, changement de
 * rôle et MFA (tracés en audit), garde anti-DELETE SQL sur
 * administration.utilisateur, RLS app_rw par invitant, matrice
 * table ↔ domaine, permissions effectives me/permissions (sentinel
 * 000…0 en posture Sprint 0).</p>
 *
 * <p>PostgreSQL : Testcontainers quand Docker est présent (CI),
 * PostgreSQL embarqué zonky sinon (poste de développement).</p>
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class BackofficeIT {

    /** L'utilisateur anonyme — posture Sprint 0 sans JWT (patron V10). */
    private static final UUID SENTINELLE =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

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
    // Chargeurs (données UNIQUES par test — la base est partagée dans la classe)
    // ------------------------------------------------------------------

    private String alea() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** Crée une structure et renvoie son id (200 attendu à l'appelant). */
    private String creerStructure(String code, String nom, String type, String region) throws Exception {
        var result = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nom":"%s","type":"%s","region":"%s",
                                 "province":"Kadiogo","commune":"Ouagadougou"}
                                """.formatted(code, nom, type, region)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }

    /** Invite un utilisateur et renvoie son id. */
    private String inviter(String email, String role, UUID invitedBy) throws Exception {
        String champInviteur = invitedBy == null ? "" : "\"invitedBy\": \"%s\",".formatted(invitedBy);
        var result = mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s"email":"%s","role":"%s","nom":"Compaore","prenoms":"Boureima"}
                                """.formatted(champInviteur, email, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }

    private ResultActions activer(String id, UUID supabaseUserId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/%s/activate".formatted(id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"supabaseUserId\": \"%s\"}".formatted(supabaseUserId)));
    }

    private ResultActions suspendre(String id, String raison) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/%s/suspend".formatted(id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"%s\"}".formatted(raison)));
    }

    private boolean auditExiste(UUID entiteId, String action) {
        Integer nombre = jdbc.queryForObject(
                "SELECT count(*) FROM audit.entry WHERE entity_id = ? AND action = ?",
                Integer.class, entiteId, action);
        return nombre != null && nombre > 0;
    }

    // ------------------------------------------------------------------
    // Structures sanitaires
    // ------------------------------------------------------------------

    @Test
    @DisplayName("structure : 201 + Location, mnémonique unique (409 même casse), GET/PATCH, 404, 400 gardes")
    void cycleDeVieStructure() throws Exception {
        String code = "CSPS-CEN-" + alea();

        // Création : 201 + Location + portrait complet.
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nom":"CSPS Sabtenga","type":"csp","region":"Centre",
                                 "province":"Kadiogo","commune":"Ouagadougou",
                                 "latitude":12.3714,"longitude":-1.5197}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString(
                        "/api/v1/organizations/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value("csp"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.latitude").value(12.3714))
                .andExpect(jsonPath("$.region").value("Centre"));

        // Mnémonique déjà prise — MÊME CASSE différente : 409 problem+json.
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nom":"CSPS Sabtenga bis","type":"csp"}
                                """.formatted(code.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code.toLowerCase()));

        // Lecture : 200 puis 404 sur un inconnu.
        String id = creerStructure("CSPS-CEN-" + alea(), "CSPS Pissy", "cs", "Centre");
        mockMvc.perform(get("/api/v1/organizations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("CSPS Pissy"))
                .andExpect(jsonPath("$.province").value("Kadiogo"));
        mockMvc.perform(get("/api/v1/organizations/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // PATCH : nom + localisation (les champs non renseignés ne bougent pas).
        mockMvc.perform(patch("/api/v1/organizations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nom":"CSPS Pissy rebaptise","latitude":12.35,"longitude":-1.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nom").value("CSPS Pissy rebaptise"))
                .andExpect(jsonPath("$.latitude").value(12.35))
                .andExpect(jsonPath("$.province").value("Kadiogo")) // inchangé
                .andExpect(jsonPath("$.type").value("cs"));         // inchangé

        // Gardes de saisie : type inconnu, géolocalisation non appariée.
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"CSPS-BAD-%s","nom":"Bad","type":"hopital"}
                                """.formatted(alea())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"CSPS-BAD-%s","nom":"Bad","type":"csp","latitude":12.3}
                                """.formatted(alea())))
                .andExpect(status().isBadRequest());

        // La création est TRACÉE (audit six dimensions).
        assertThat(auditExiste(UUID.fromString(id), "STRUCTURE_CREATED")).isTrue();
    }

    @Test
    @DisplayName("structure : désactivation/réactivation SOFT, 409 sur double, jamais de DELETE")
    void activationStructure409SurDouble() throws Exception {
        String id = creerStructure("CHU-CEN-" + alea(), "CHU Yalgado", "chu", "Centre");

        mockMvc.perform(post("/api/v1/organizations/{id}/deactivate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        // Double désactivation : 409 explicite (from=inactive).
        mockMvc.perform(post("/api/v1/organizations/{id}/deactivate", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("inactive"));

        mockMvc.perform(post("/api/v1/organizations/{id}/activate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        // Double réactivation : 409 explicite (from=active).
        mockMvc.perform(post("/api/v1/organizations/{id}/activate", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("active"));

        assertThat(auditExiste(UUID.fromString(id), "STRUCTURE_DEACTIVATED")).isTrue();
        assertThat(auditExiste(UUID.fromString(id), "STRUCTURE_ACTIVATED")).isTrue();
    }

    @Test
    @DisplayName("structure : recherche par région/type/texte/active + arborescence par région")
    void rechercheEtArborescence() throws Exception {
        // Régions UNIQUES par test : la base est partagée entre les méthodes
        // de la classe — les filtres doivent rester prédictibles.
        String alea = alea();
        String regionA = "RegA-" + alea;
        String regionB = "RegB-" + alea;
        String s1 = creerStructure("CSPS-CEN-" + alea + "a", "CSPS Sabtenga-" + alea, "csp", regionA);
        creerStructure("CHU-CEN-" + alea + "b", "CHU Yalgado", "chu", regionA);
        creerStructure("CSPS-HBA-" + alea + "c", "CSPS Bobo Diaradougou", "csp", regionB);
        String s4 = creerStructure("PHA-HBA-" + alea + "d", "Pharmacie du grand marche", "pharmacy",
                regionB);
        mockMvc.perform(post("/api/v1/organizations/{id}/deactivate", s4))
                .andExpect(status().isOk());

        // Région (insensible à la casse) : les deux structures de regionA.
        mockMvc.perform(get("/api/v1/organizations").param("region", regionA.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // Région + type combinés : le seul CHU de regionA.
        mockMvc.perform(get("/api/v1/organizations")
                        .param("region", regionA).param("type", "chu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nom").value("CHU Yalgado"));

        // Type + région csp : la seule structure de regionA.
        mockMvc.perform(get("/api/v1/organizations")
                        .param("region", regionA).param("type", "csp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(s1));

        // Texte : contenu insensible à la casse, dans le code OU le nom.
        mockMvc.perform(get("/api/v1/organizations").param("texte", ("Sabtenga-" + alea).toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(s1));
        mockMvc.perform(get("/api/v1/organizations").param("texte", alea.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));

        // Active, bornée à regionB : seule la pharmacie désactivée.
        mockMvc.perform(get("/api/v1/organizations")
                        .param("region", regionB).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(s4));
        mockMvc.perform(get("/api/v1/organizations")
                        .param("region", regionB).param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("%s".formatted(
                        // id de la structure csp de regionB — relue par filtre
                        jdbc.queryForObject("SELECT id::text FROM organization.structure "
                                + "WHERE code LIKE ?", String.class,
                                "CSPS-HBA-" + alea + "c"))));

        // Arborescence : regionA = 2 total / 2 actives ; regionB = 2 / 1.
        var arbre = mockMvc.perform(get("/api/v1/organizations/arborescence"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var lignes = JsonPath.<java.util.List<Map<String, Object>>>read(arbre, "$");
        var ligneA = lignes.stream()
                .filter(l -> regionA.equals(l.get("region")))
                .findFirst().orElseThrow();
        assertThat(((Number) ligneA.get("total")).intValue()).isEqualTo(2);
        assertThat(((Number) ligneA.get("actives")).intValue()).isEqualTo(2);
        var ligneB = lignes.stream()
                .filter(l -> regionB.equals(l.get("region")))
                .findFirst().orElseThrow();
        assertThat(((Number) ligneB.get("total")).intValue()).isEqualTo(2);
        assertThat(((Number) ligneB.get("actives")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> parType = (Map<String, Object>) ligneB.get("parType");
        assertThat(((Number) parType.get("pharmacy")).intValue()).isEqualTo(1);

        // Filtres illisibles : 400 propre et local.
        mockMvc.perform(get("/api/v1/organizations").param("active", "peut-etre"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/organizations").param("type", "hopital"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Utilisateurs — invitation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invitation : 201 (status invite, sans compte Supabase), 409 doublon email même casse")
    void invitationUtilisateur409DoublonEmail() throws Exception {
        String structureId = creerStructure("CSPS-INV-" + alea(), "CSPS Tengsoba", "csp", "Centre");
        String email = "compaore." + alea() + "@minsante.bf";

        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"medecin","nom":"Compaore","prenoms":"Boureima",
                                 "structureId":"%s"}
                                """.formatted(email, structureId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/api/v1/admin/users/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("invite"))
                .andExpect(jsonPath("$.role").value("medecin"))
                .andExpect(jsonPath("$.supabaseUserId").value(
                        org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.mfaActive").value(false))
                .andExpect(jsonPath("$.structureId").value(structureId));

        // Doublon, MÊME CASSE différente : 409 clair portant le statut —
        // réinviter n'est JAMAIS un rejeu silencieux.
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"infirmier","nom":"Compaore"}
                                """.formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.statutExistant").value("invite"));

        // Structure de rattachement inconnue (loi n°3 : référence logique
        // vérifiée par le port) : 404, jamais de référence pendouillante.
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fantome.%s@minsante.bf","role":"medecin","nom":"Fantome",
                                 "structureId":"%s"}
                                """.formatted(alea(), UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // Rôle hors nomenclature : 400.
        mockMvc.perform(post("/api/v1/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"chirurgien.%s@minsante.bf","role":"chirurgien","nom":"X"}
                                """.formatted(alea())))
                .andExpect(status().isBadRequest());

        // Liste : filtre role — l'utilisateur créé remonte (la base est
        // partagée entre les méthodes : on asserte la PRÉSENCE, pas le décompte).
        mockMvc.perform(get("/api/v1/admin/users").param("role", "medecin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem(email)));

        // Utilisateur inconnu : 404.
        mockMvc.perform(get("/api/v1/admin/users/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Utilisateurs — activation & liaison Supabase
    // ------------------------------------------------------------------

    @Test
    @DisplayName("activation : lie supabaseUserId (définitif), 409 double liaison — même compte, autre utilisateur")
    void activationLiaisonSupabase409DoubleLiaison() throws Exception {
        String emailA = "actif-a." + alea() + "@minsante.bf";
        String emailB = "actif-b." + alea() + "@minsante.bf";
        String a = inviter(emailA, "medecin", null);
        String b = inviter(emailB, "infirmier", null);
        UUID compteSupabase = UUID.randomUUID();

        // Première liaison : 200, statut actif, miroir posé, première
        // connexion datée.
        activer(a, compteSupabase)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("actif"))
                .andExpect(jsonPath("$.supabaseUserId").value(compteSupabase.toString()))
                .andExpect(jsonPath("$.lastLoginAt").exists());

        // Double liaison SUR UN AUTRE utilisateur : 409 avec le porteur.
        activer(b, compteSupabase)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.supabaseUserId").value(compteSupabase.toString()))
                .andExpect(jsonPath("$.utilisateurPorteur").value(a));

        // Re-activation du même utilisateur (compte déjà lié) : 409.
        activer(a, UUID.randomUUID())
                .andExpect(status().isConflict());

        // Filtre par statut : A est actif (B est resté invité — sa liaison a
        // été refusée, rien n'a bougé).
        mockMvc.perform(get("/api/v1/admin/users").param("status", "actif"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem(emailA)))
                .andExpect(jsonPath("$[*].email", org.hamcrest.Matchers.not(hasItem(emailB))));
        mockMvc.perform(get("/api/v1/admin/users/{id}", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("invite"))
                .andExpect(jsonPath("$.supabaseUserId").value(
                        org.hamcrest.Matchers.nullValue()));
    }

    // ------------------------------------------------------------------
    // Utilisateurs — suspension / réactivation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("suspension : motif obligatoire tracé en audit, 409 double, réactivation, 409 double")
    void suspensionReactivation() throws Exception {
        String id = inviter("suspendu." + alea() + "@minsante.bf", "agent_financier", null);
        activer(id, UUID.randomUUID()).andExpect(status().isOk());

        // Suspendre un INVITÉ (jamais activé) : transition illégale, 409.
        String invite = inviter("jamais-active." + alea() + "@minsante.bf", "medecin", null);
        suspendre(invite, "Motif de dix caracteres")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("invite"));

        // Motif trop court : 400 (le motif est la trace réglementaire).
        suspendre(id, "court")
                .andExpect(status().isBadRequest());

        // Suspension : 200 + suspendu ; le motif vit dans l'audit.
        String motif = "Depart definitif du poste, compte a garder";
        suspendre(id, motif)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("suspendu"));
        String raisonEnAudit = jdbc.queryForObject(
                "SELECT reason FROM audit.entry WHERE entity_id = ? AND action = 'UTILISATEUR_SUSPENDED'",
                String.class, UUID.fromString(id));
        assertThat(raisonEnAudit).isEqualTo(motif);

        // Double suspension : 409.
        suspendre(id, motif)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("suspendu"));

        // Réactivation : 200 + actif ; double : 409.
        mockMvc.perform(post("/api/v1/admin/users/{id}/reactivate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("actif"));
        mockMvc.perform(post("/api/v1/admin/users/{id}/reactivate", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.from").value("actif"));

        assertThat(auditExiste(UUID.fromString(id), "UTILISATEUR_REACTIVATED")).isTrue();
    }

    // ------------------------------------------------------------------
    // Utilisateurs — changement de rôle & MFA (tracés)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("changement de rôle : 200 + trace audit from/to ; rôle inconnu : 400")
    void changementRoleTrace() throws Exception {
        String id = inviter("role." + alea() + "@minsante.bf", "medecin", null);

        mockMvc.perform(post("/api/v1/admin/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"superviseur\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("superviseur"));

        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit.entry WHERE entity_id = ? AND action = 'UTILISATEUR_ROLE_CHANGED'",
                String.class, UUID.fromString(id));
        assertThat(details).contains("medecin").contains("superviseur");

        mockMvc.perform(post("/api/v1/admin/users/{id}/role", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"chirurgien\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("MFA : bascule activée/désactivée + traces audit dédiées")
    void mfaToggleTrace() throws Exception {
        String id = inviter("mfa." + alea() + "@minsante.bf", "pharmacien", null);

        mockMvc.perform(post("/api/v1/admin/users/{id}/mfa", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaActive").value(true));
        assertThat(auditExiste(UUID.fromString(id), "UTILISATEUR_MFA_ENABLED")).isTrue();

        mockMvc.perform(post("/api/v1/admin/users/{id}/mfa", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaActive").value(false));
        assertThat(auditExiste(UUID.fromString(id), "UTILISATEUR_MFA_DISABLED")).isTrue();

        // active absent : 400.
        mockMvc.perform(post("/api/v1/admin/users/{id}/mfa", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Garde SQL — append-only administratif
    // ------------------------------------------------------------------

    @Test
    @DisplayName("garde anti-DELETE : administration.utilisateur refuse la suppression (SQL direct)")
    void gardeAntiDeleteUtilisateur() throws Exception {
        String email = "a-supprimer." + alea() + "@minsante.bf";
        String id = inviter(email, "medecin", null);
        // On SUSPEND, on ne DELETE jamais — le trigger V12 crie.
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM administration.utilisateur WHERE id = ?",
                        UUID.fromString(id)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        // La ligne est toujours là.
        Integer restants = jdbc.queryForObject(
                "SELECT count(*) FROM administration.utilisateur WHERE id = ?",
                Integer.class, UUID.fromString(id));
        assertThat(restants).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // RLS — administration.utilisateur (invited_by, style V10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RLS app_rw : un invitant ne voit QUE les utilisateurs qu'il a invités")
    void rlsUtilisateurParInvitant() throws Exception {
        UUID invitant = UUID.randomUUID();
        UUID autre = UUID.randomUUID();
        // Deux invitations par l'invitant, une par un autre.
        inviter("rls-1." + alea() + "@minsante.bf", "medecin", invitant);
        inviter("rls-2." + alea() + "@minsante.bf", "infirmier", invitant);
        inviter("rls-3." + alea() + "@minsante.bf", "medecin", autre);

        // En tant que l'invitant : exactement SES deux utilisateurs.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = '" + invitant + "'");
            assertThat(compter(connexion,
                    "SELECT count(*) FROM administration.utilisateur WHERE invited_by = '"
                            + invitant + "'")).isEqualTo(2);
        }
        // En tant qu'un autre : les invitations de l'invitant sont INVISIBLES.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = '" + autre + "'");
            assertThat(compter(connexion,
                    "SELECT count(*) FROM administration.utilisateur WHERE invited_by = '"
                            + invitant + "'")).isZero();
        }
        // Sans identité : fail-closed, aucune ligne.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = ''");
            assertThat(compter(connexion, "SELECT count(*) FROM administration.utilisateur"))
                    .isZero();
        }
        // Rôle admin (GUC app.roles, style V5/V10) : tout relire.
        try (Connection connexion = connexionNue()) {
            executer(connexion, "SET ROLE app_rw");
            executer(connexion, "SET app.user_id = ''");
            executer(connexion, "SET app.roles = 'admin'");
            assertThat(compter(connexion,
                    "SELECT count(*) FROM administration.utilisateur WHERE invited_by IN ('"
                            + invitant + "', '" + autre + "')")).isEqualTo(3);
        }
    }

    // ------------------------------------------------------------------
    // Matrice rôle → permissions : table V12 = domaine pur
    // ------------------------------------------------------------------

    @Test
    @DisplayName("matrice de permissions : administration.role_permission est l'exact miroir de RolesPermissions")
    void matriceTableEgaleDomaine() {
        Map<String, Set<String>> attendue = new HashMap<>();
        for (var entree : RolesPermissions.matrice().entrySet()) {
            attendue.put(entree.getKey().getCode(), entree.getValue());
        }
        Map<String, Set<String>> lue = new HashMap<>();
        jdbc.query("SELECT role, permission FROM administration.role_permission", rs -> {
            lue.computeIfAbsent(rs.getString("role"), r -> new HashSet<>())
                    .add(rs.getString("permission"));
        });
        // 27 lignes au total, aucun rôle ni permission en trop, aucun en moins.
        assertThat(lue).isEqualTo(attendue);
    }

    // ------------------------------------------------------------------
    // Permissions effectives — me/permissions (sentinel 000…0)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("me/permissions : sentinel 000…0 (sans JWT) → rôle admin → admin:gerer ; suspendu → fail-closed")
    void permissionsContexte() throws Exception {
        // Avant toute liaison : l'appelant sentinel est inconnu, permissions vides.
        mockMvc.perform(get("/api/v1/admin/me/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utilisateurConnu").value(false))
                .andExpect(jsonPath("$.permissions", hasSize(0)));

        // Un admin dont le compte Supabase EST le sentinel (posture
        // Sprint 0 : le compte anonyme matérialisé, patron V10).
        String id = inviter("admin-sentinel." + alea() + "@minsante.bf", "admin", null);
        activer(id, SENTINELLE).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/me/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utilisateurConnu").value(true))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.status").value("actif"))
                .andExpect(jsonPath("$.supabaseUserId").value(SENTINELLE.toString()))
                .andExpect(jsonPath("$.permissions", hasSize(16)))
                .andExpect(jsonPath("$.permissions", hasItem("admin:gerer")))
                .andExpect(jsonPath("$.permissions", hasItem("paiement:reconcilier")));

        // La suspension COUPE TOUT : fail-closed, même pour l'admin.
        suspendre(id, "Suspension de controle du fail-closed")
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/me/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.utilisateurConnu").value(true))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.status").value("suspendu"))
                .andExpect(jsonPath("$.permissions", hasSize(0)));
    }

    // ------------------------------------------------------------------
    // Connexions JDBC pures (SET ROLE app_rw — preuve RLS, patron RlsAppRwIT)
    // ------------------------------------------------------------------

    private Connection connexionNue() throws SQLException {
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
}
