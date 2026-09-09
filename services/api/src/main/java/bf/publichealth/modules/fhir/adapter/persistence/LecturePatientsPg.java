package bf.publichealth.modules.fhir.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.fhir.application.LecturePatientsFhir;

/**
 * Adaptateur de lecture du MPI (schéma identity) — patron
 * {@code MiroirPatientsPg} : SELECT mono-schéma, requêtes dynamiques à
 * paramètres nommés, enrichissement des noms/télécoms/identifiants en
 * requêtes plates séparées. Aucun JOIN inter-schémas, aucune écriture.
 */
@Repository
public class LecturePatientsPg implements LecturePatientsFhir {

    private static final String COLONNES = """
            p.id, p.ph_reference, p.active, p.master_id, p.gender, p.birth_date,
            p.birth_date_approximative, p.created_at, p.updated_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public LecturePatientsPg(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientFhir> parId(UUID id) {
        List<Ligne> lignes = jdbc.query(
                "SELECT " + COLONNES + " FROM identity.patient p WHERE p.id = :id",
                Map.of("id", id),
                (rs, i) -> mapperLigne(rs));
        if (lignes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(enrichir(lignes).get(0));
    }

    // ------------------------------------------------------------------
    // Recherche miroir
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ResultatRecherche<PatientFhir> rechercher(CriteresPatient criteres, int limite, int decalage) {
        // Les dossiers fusionnés (master_id) et inactifs n'existent pas pour
        // la recherche — même règle que PatientService.search (E1).
        // NB : les blocs collés ci-dessous commencent TOUS par « AND » avec
        // un espace explicite — le collage text block ne le garantit pas.
        StringBuilder where = new StringBuilder(" WHERE p.active = true AND p.master_id IS NULL");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (criteres.family() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM identity.patient_name n")
                    .append(" WHERE n.patient_id = p.id")
                    .append(" AND lower(n.family) LIKE lower(:family) || '%')");
            params.addValue("family", criteres.family());
        }
        if (criteres.given() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM identity.patient_name n")
                    .append(" WHERE n.patient_id = p.id")
                    .append(" AND lower(n.given) LIKE lower(:given) || '%')");
            params.addValue("given", criteres.given());
        }
        if (criteres.phone() != null) {
            // Token FHIR : valeur EXACTE (les numéros sont des chiffres ; la
            // casse n'a aucun sens et l'index idx_patient_telecom_value sert).
            where.append(" AND EXISTS (SELECT 1 FROM identity.patient_telecom t")
                    .append(" WHERE t.patient_id = p.id")
                    .append(" AND t.value = :phone)");
            params.addValue("phone", criteres.phone());
        }
        if (criteres.birthDate() != null) {
            where.append(" AND p.birth_date = :naissance");
            params.addValue("naissance", criteres.birthDate());
        }
        String systemeIdent = criteres.systemeIdentifiant();
        if (criteres.valeurIdentifiant() != null) {
            if ("PH".equals(systemeIdent)) {
                where.append(" AND p.ph_reference = :valeurIdent");
            } else if (systemeIdent != null) {
                where.append(" AND EXISTS (SELECT 1 FROM identity.patient_identifier i")
                        .append(" WHERE i.patient_id = p.id AND i.system = :systemeIdent")
                        .append(" AND i.value = :valeurIdent)");
                params.addValue("systemeIdent", systemeIdent);
            } else {
                // Valeur seule : ph_reference OU n'importe quel identifiant.
                where.append(" AND (p.ph_reference = :valeurIdent")
                        .append(" OR EXISTS (SELECT 1 FROM identity.patient_identifier i")
                        .append(" WHERE i.patient_id = p.id")
                        .append(" AND i.value = :valeurIdent))");
            }
            params.addValue("valeurIdent", criteres.valeurIdentifiant());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM identity.patient p" + where, params, Long.class);
        List<Ligne> lignes = jdbc.query(
                "SELECT " + COLONNES + " FROM identity.patient p" + where
                        + " ORDER BY p.created_at DESC, p.id ASC LIMIT :limite OFFSET :decalage",
                params.addValue("limite", limite).addValue("decalage", decalage),
                (rs, i) -> mapperLigne(rs));
        return new ResultatRecherche<>(enrichir(lignes), total == null ? 0 : total);
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private record Ligne(UUID id, String phReference, boolean active, UUID masterId, String gender,
                        LocalDate birthDate, boolean birthDateApproximative,
                        Instant createdAt, Instant updatedAt) {
    }

    private static Ligne mapperLigne(ResultSet rs) throws SQLException {
        return new Ligne(
                rs.getObject("id", UUID.class),
                rs.getString("ph_reference"),
                rs.getBoolean("active"),
                rs.getObject("master_id", UUID.class),
                rs.getString("gender"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getBoolean("birth_date_approximative"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** Enrichit chaque dossier : noms, télécoms, identifiants (3 requêtes plates). */
    private List<PatientFhir> enrichir(List<Ligne> lignes) {
        if (lignes.isEmpty()) {
            return List.of();
        }
        List<UUID> identifiants = lignes.stream().map(Ligne::id).toList();
        Map<UUID, List<NomFhir>> noms = new HashMap<>();
        jdbc.query("""
                        SELECT patient_id, use, family, given FROM identity.patient_name
                        WHERE patient_id IN (:ids) ORDER BY use, id""",
                Map.of("ids", identifiants),
                rs -> {
                    noms.computeIfAbsent(rs.getObject("patient_id", UUID.class), k -> new ArrayList<>())
                            .add(new NomFhir(rs.getString("use"), rs.getString("family"),
                                    rs.getString("given")));
                });
        Map<UUID, List<TelecomFhir>> telecoms = new HashMap<>();
        jdbc.query("""
                        SELECT patient_id, system, value, use FROM identity.patient_telecom
                        WHERE patient_id IN (:ids) ORDER BY id""",
                Map.of("ids", identifiants),
                rs -> {
                    telecoms.computeIfAbsent(rs.getObject("patient_id", UUID.class), k -> new ArrayList<>())
                            .add(new TelecomFhir(rs.getString("system"), rs.getString("value"),
                                    rs.getString("use")));
                });
        Map<UUID, List<IdentifiantFhir>> identifiantsNationaux = new HashMap<>();
        jdbc.query("""
                        SELECT patient_id, system, value FROM identity.patient_identifier
                        WHERE patient_id IN (:ids) ORDER BY system, id""",
                Map.of("ids", identifiants),
                rs -> {
                    identifiantsNationaux
                            .computeIfAbsent(rs.getObject("patient_id", UUID.class), k -> new ArrayList<>())
                            .add(new IdentifiantFhir(rs.getString("system"), rs.getString("value")));
                });

        return lignes.stream()
                .map(l -> new PatientFhir(l.id(), l.phReference(), l.active(), l.masterId(),
                        l.gender(), l.birthDate(), l.birthDateApproximative(), l.createdAt(),
                        l.updatedAt(),
                        noms.getOrDefault(l.id(), List.of()),
                        telecoms.getOrDefault(l.id(), List.of()),
                        identifiantsNationaux.getOrDefault(l.id(), List.of())))
                .toList();
    }
}
