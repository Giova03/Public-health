package bf.publichealth.modules.hub.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.hub.application.EntrepotHub;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.EvenementOutbox;
import bf.publichealth.modules.hub.domain.MessageHub;

/**
 * Adaptateur PostgreSQL du module hub — JdbcTemplate pur (contrôle exact
 * des requêtes, jsonb maîtrisés, GREATEST pour le filigrane). Le schéma
 * hub ET la fenêtre mono-schema sur sync.outbox vivent ici : lecture
 * ({@code published = false}) et marquage dans la MÊME transaction que
 * la création des messages (patron accepté MiroirPatientsPg — jamais de
 * JOIN inter-schemas).
 */
@Repository
public class EntrepotHubPg implements EntrepotHub {

    private final JdbcTemplate jdbc;

    public EntrepotHubPg(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLONNES_DESTINATION =
            "id, code, base_url, actif, watermark, created_at";

    // ------------------------------------------------------------------
    // Destinations
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<Destination> destinationsActives() {
        return jdbc.query(
                "SELECT " + COLONNES_DESTINATION + " FROM hub.destination "
                        + "WHERE actif = true ORDER BY id",
                (rs, i) -> destination(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Destination> toutesDestinations() {
        return jdbc.query(
                "SELECT " + COLONNES_DESTINATION + " FROM hub.destination ORDER BY code",
                (rs, i) -> destination(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Destination> destinationParCode(String code) {
        return jdbc.query(
                "SELECT " + COLONNES_DESTINATION + " FROM hub.destination WHERE code = ?",
                (rs, i) -> destination(rs), code).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Destination> destinationParId(UUID id) {
        return jdbc.query(
                "SELECT " + COLONNES_DESTINATION + " FROM hub.destination WHERE id = ?",
                (rs, i) -> destination(rs), id).stream().findFirst();
    }

    @Override
    public Optional<Destination> verrouillerDestination(UUID id) {
        // FOR UPDATE : verrou applicatif par destination — sérialise
        // l'attribution des séquences (drain) et les avancées de filigrane.
        // DOIT être appelé dans une transaction (draineur / livreur).
        return jdbc.query(
                "SELECT " + COLONNES_DESTINATION + " FROM hub.destination WHERE id = ? FOR UPDATE",
                (rs, i) -> destination(rs), id).stream().findFirst();
    }

    @Override
    public Destination creerDestination(String code, String baseUrl, boolean actif) {
        UUID id = UuidV7.next();
        OffsetDateTime cree = jdbc.queryForObject(
                "INSERT INTO hub.destination (id, code, base_url, actif, watermark) "
                        + "VALUES (?, ?, ?, ?, 0) RETURNING created_at",
                OffsetDateTime.class, id, code, baseUrl, actif);
        return new Destination(id, code, baseUrl, actif, 0, cree.toInstant());
    }

    // ------------------------------------------------------------------
    // Outbox (fenêtre mono-schema sur sync.outbox — V4)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<EvenementOutbox> evenementsNonPublies(int limite) {
        return jdbc.query(
                "SELECT event_id, event_type, aggregate_id, payload::text, occurred_at "
                        + "FROM sync.outbox WHERE published = false "
                        + "ORDER BY occurred_at ASC, event_id ASC LIMIT ?",
                (rs, i) -> new EvenementOutbox(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        instant(rs, "occurred_at")),
                limite);
    }

    @Override
    public int marquerPublies(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        // Mono-schema, même transaction locale que la création des messages :
        // jamais de divergence outbox ↔ file HUB.
        UUID[] ids = eventIds.toArray(new UUID[0]);
        String emplacements = String.join(",", java.util.Collections.nCopies(ids.length, "?"));
        return jdbc.update("UPDATE sync.outbox SET published = true WHERE event_id IN ("
                + emplacements + ")", (Object[]) ids);
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    @Override
    public long sequenceMax(UUID destinationId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM hub.message WHERE destination_id = ?",
                Long.class, destinationId);
        return max == null ? 0L : max;
    }

    @Override
    public void enregistrerMessage(MessageHub message) {
        jdbc.update("""
                INSERT INTO hub.message (id, event_id, destination_id, sequence, envelope,
                                         signature, status, attempts, last_error,
                                         next_attempt_at, sent_at, acked_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                message.id(), message.eventId(), message.destinationId(), message.sequence(),
                message.envelope(), message.signature(), message.status(), message.attempts(),
                message.lastError(), offsetDateTime(message.nextAttemptAt()),
                offsetDateTime(message.sentAt()), offsetDateTime(message.ackedAt()),
                offsetDateTime(message.createdAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageHub> messagesDus(UUID destinationId, int limite) {
        return jdbc.query("""
                        SELECT id, event_id, destination_id, sequence, envelope::text AS envelope,
                               signature, status, attempts, last_error, next_attempt_at,
                               sent_at, acked_at, created_at
                        FROM hub.message
                        WHERE destination_id = ?
                          AND status IN ('queued','sent')
                          AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                        ORDER BY sequence ASC
                        LIMIT ?
                        """,
                (rs, i) -> message(rs),
                destinationId, OffsetDateTime.now(ZoneOffset.UTC), limite);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageAvecDestination> messageParId(UUID id) {
        return jdbc.query(requeteMessages() + " WHERE m.id = ?",
                (rs, i) -> messageAvecDestination(rs), id).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageAvecDestination> messages(String status, String destinationCode, int limite) {
        List<Object> parametres = new ArrayList<>();
        StringBuilder sql = new StringBuilder(requeteMessages()).append(" WHERE 1=1");
        if (status != null) {
            sql.append(" AND m.status = ?");
            parametres.add(status);
        }
        if (destinationCode != null) {
            sql.append(" AND d.code = ?");
            parametres.add(destinationCode);
        }
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT ?");
        parametres.add(limite);
        return jdbc.query(sql.toString(), (rs, i) -> messageAvecDestination(rs),
                parametres.toArray());
    }

    @Override
    public void marquerEnvoye(UUID messageId, Instant envoyeA, Instant repriseA) {
        jdbc.update("UPDATE hub.message SET status = 'sent', sent_at = ?, next_attempt_at = ? "
                        + "WHERE id = ?",
                offsetDateTime(envoyeA), offsetDateTime(repriseA), messageId);
    }

    @Override
    public void marquerAcquitte(UUID messageId, Instant acquitteA) {
        jdbc.update("UPDATE hub.message SET status = 'acked', acked_at = ?, next_attempt_at = NULL "
                        + "WHERE id = ?",
                offsetDateTime(acquitteA), messageId);
    }

    @Override
    public void marquerEchec(UUID messageId, int nouveauxAttempts, Instant prochainEssai,
                             String erreur) {
        jdbc.update("UPDATE hub.message SET status = 'queued', attempts = ?, "
                        + "next_attempt_at = ?, last_error = ? WHERE id = ?",
                nouveauxAttempts, offsetDateTime(prochainEssai), erreur, messageId);
    }

    @Override
    public void marquerMort(UUID messageId, int tentativesEffectuees, Instant a, String raison) {
        jdbc.update("UPDATE hub.message SET status = 'dead', attempts = ?, last_error = ?, "
                        + "next_attempt_at = NULL WHERE id = ?",
                tentativesEffectuees, raison, messageId);
    }

    @Override
    public void remettreEnFile(UUID messageId, Instant maintenant) {
        jdbc.update("UPDATE hub.message SET status = 'queued', attempts = 0, "
                        + "next_attempt_at = ? WHERE id = ?",
                offsetDateTime(maintenant), messageId);
    }

    @Override
    public void journaliser(UUID messageId, int attempt, String outcome, String detail) {
        jdbc.update("INSERT INTO hub.delivery_log (id, message_id, attempt, outcome, detail, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UuidV7.next(), messageId, attempt, outcome, detail,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------
    // Filigrane & supervision
    // ------------------------------------------------------------------

    @Override
    public void avancerWatermark(UUID destinationId, long sequence) {
        // Strictement monotone : GREATEST, jamais en arrière.
        jdbc.update("UPDATE hub.destination SET watermark = GREATEST(watermark, ?) WHERE id = ?",
                sequence, destinationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> filigranesParCode() {
        Map<String, Long> filigranes = new LinkedHashMap<>();
        jdbc.query("SELECT code, watermark FROM hub.destination ORDER BY code",
                rs -> {
                    filigranes.put(rs.getString("code"), rs.getLong("watermark"));
                });
        return filigranes;
    }

    @Override
    @Transactional(readOnly = true)
    public StatsDestination statsDestination(UUID destinationId) {
        Map<String, Long> comptages = new HashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS total FROM hub.message WHERE destination_id = ? "
                        + "GROUP BY status",
                rs -> {
                    comptages.put(rs.getString("status"), rs.getLong("total"));
                }, destinationId);
        return new StatsDestination(
                comptages.getOrDefault("queued", 0L),
                comptages.getOrDefault("sent", 0L),
                comptages.getOrDefault("acked", 0L),
                comptages.getOrDefault("dead", 0L));
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private static String requeteMessages() {
        return """
                SELECT m.id, m.event_id, m.destination_id, m.sequence,
                       m.envelope::text AS envelope, m.signature, m.status, m.attempts,
                       m.last_error, m.next_attempt_at, m.sent_at, m.acked_at, m.created_at,
                       d.code AS destination_code
                FROM hub.message m
                JOIN hub.destination d ON d.id = m.destination_id
                """;
    }

    private static Destination destination(ResultSet rs) throws SQLException {
        return new Destination(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("base_url"),
                rs.getBoolean("actif"),
                rs.getLong("watermark"),
                instant(rs, "created_at"));
    }

    private static MessageHub message(ResultSet rs) throws SQLException {
        return new MessageHub(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("destination_id", UUID.class),
                rs.getLong("sequence"),
                rs.getString("envelope"),
                rs.getString("signature"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                instant(rs, "next_attempt_at"),
                instant(rs, "sent_at"),
                instant(rs, "acked_at"),
                instant(rs, "created_at"));
    }

    private static MessageAvecDestination messageAvecDestination(ResultSet rs) throws SQLException {
        return new MessageAvecDestination(message(rs), rs.getString("destination_code"));
    }

    private static Instant instant(ResultSet rs, String colonne) throws SQLException {
        OffsetDateTime valeur = rs.getObject(colonne, OffsetDateTime.class);
        return valeur == null ? null : valeur.toInstant();
    }

    private static OffsetDateTime offsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
