package bf.publichealth.modules.sync.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.sync.application.MiroirPatients;
import bf.publichealth.modules.sync.domain.CurseurDelta;

/**
 * Miroir descendant patient — lecture keyset (updated_at, id) du schéma
 * identity, sans JOIN inter-schemas (noms et télécoms ramenés en deux
 * requêtes plates). Voir la note d'architecture sur {@link MiroirPatients}.
 */
@Repository
public class MiroirPatientsPg implements MiroirPatients {

    private final JdbcTemplate jdbc;

    public MiroirPatientsPg(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> idPatientParClientRequestId(UUID clientRequestId) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM identity.patient WHERE client_request_id = ? LIMIT 1",
                UUID.class, clientRequestId);
        return ids.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public PageDelta patientsApres(CurseurDelta curseur, int limite) {
        // Une ligne de plus que demandé : le trop-plein révèle hasMore.
        List<Ligne> lignes = jdbc.query("""
                SELECT id, ph_reference, active, master_id, gender, birth_date,
                       birth_date_approximative, version, updated_at
                FROM identity.patient
                WHERE (updated_at, id) > (?, ?)
                ORDER BY updated_at ASC, id ASC
                LIMIT ?
                """,
                (rs, i) -> nouvelleLigne(rs),
                OffsetDateTime.ofInstant(curseur.instant(), ZoneOffset.UTC),
                curseur.identifiant(),
                limite + 1);

        boolean hasMore = lignes.size() > limite;
        List<Ligne> page = hasMore ? lignes.subList(0, limite) : lignes;
        if (page.isEmpty()) {
            return new PageDelta(curseur, false, List.of());
        }

        Map<UUID, List<Nom>> noms = chargerNoms(page);
        Map<UUID, List<Telecom>> telecoms = chargerTelecoms(page);
        List<PatientMiroir> patients = page.stream()
                .map(l -> new PatientMiroir(l.id, l.phReference, l.active, l.masterId, l.gender,
                        l.birthDate, l.birthDateApproximative, l.version, l.updatedAt,
                        noms.getOrDefault(l.id, List.of()),
                        telecoms.getOrDefault(l.id, List.of())))
                .toList();

        Ligne derniere = page.get(page.size() - 1);
        CurseurDelta nouveauCurseur = new CurseurDelta(derniere.updatedAt, derniere.id);
        return new PageDelta(nouveauCurseur, hasMore, patients);
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private record Ligne(UUID id, String phReference, boolean active, UUID masterId,
                        String gender, LocalDate birthDate, boolean birthDateApproximative,
                        long version, Instant updatedAt) {
    }

    private static Ligne nouvelleLigne(ResultSet rs) throws SQLException {
        return new Ligne(
                rs.getObject("id", UUID.class),
                rs.getString("ph_reference"),
                rs.getBoolean("active"),
                rs.getObject("master_id", UUID.class),
                rs.getString("gender"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getBoolean("birth_date_approximative"),
                rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private Map<UUID, List<Nom>> chargerNoms(List<Ligne> page) {
        Map<UUID, List<Nom>> noms = new HashMap<>();
        UUID[] identifiants = page.stream().map(Ligne::id).toArray(UUID[]::new);
        if (identifiants.length == 0) {
            return noms;
        }
        jdbc.query("SELECT patient_id, use, family, given FROM identity.patient_name WHERE patient_id IN ("
                        + emplacements(identifiants) + ")",
                rs -> {
                    noms.computeIfAbsent(rs.getObject("patient_id", UUID.class), k -> new ArrayList<>())
                            .add(new Nom(rs.getString("use"), rs.getString("family"),
                                    rs.getString("given")));
                },
                (Object[]) identifiants);
        return noms;
    }

    private Map<UUID, List<Telecom>> chargerTelecoms(List<Ligne> page) {
        Map<UUID, List<Telecom>> telecoms = new HashMap<>();
        UUID[] identifiants = page.stream().map(Ligne::id).toArray(UUID[]::new);
        if (identifiants.length == 0) {
            return telecoms;
        }
        jdbc.query("SELECT patient_id, system, value, use FROM identity.patient_telecom WHERE patient_id IN ("
                        + emplacements(identifiants) + ")",
                rs -> {
                    telecoms.computeIfAbsent(rs.getObject("patient_id", UUID.class), k -> new ArrayList<>())
                            .add(new Telecom(rs.getString("system"), rs.getString("value"),
                                    rs.getString("use")));
                },
                (Object[]) identifiants);
        return telecoms;
    }

    private static String emplacements(UUID[] identifiants) {
        return String.join(",", Collections.nCopies(identifiants.length, "?"));
    }
}
