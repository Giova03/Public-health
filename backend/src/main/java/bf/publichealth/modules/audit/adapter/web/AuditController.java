package bf.publichealth.modules.audit.adapter.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API audit — /api/v1/audit/entries (V14, I16).
 *
 * <p>La chaîne SHA-256 append-only existait depuis V5 mais n'était
 * exposée NULLE part : le superviseur — le rôle le plus important d'un
 * système national selon l'audit — n'avait aucun écran. Ce endpoint
 * lit les dernières entrées (audit:lire : superviseur, admin), avec
 * filtres action/patient. La lecture du journal est elle-même
 * journalisée (QUI surveille les surveillants).</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/entries")
    public List<Map<String, Object>> entries(
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "100") int limite) {
        int limiteSaine = Math.min(Math.max(limite, 1), 500);
        StringBuilder sql = new StringBuilder("""
                SELECT occurred_at, actor_id, action, entity, entity_id, facility_id,
                       reason, result, details
                  FROM audit.entry
                """);
        var parametres = new java.util.ArrayList<Object>();
        if (action != null && !action.isBlank()) {
            sql.append(" WHERE action = ?");
            parametres.add(action);
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ").append(limiteSaine);

        List<Map<String, Object>> lignes = jdbc.queryForList(sql.toString(),
                parametres.toArray());
        // La lecture du journal est journalisée (REQUIRES_NEW côté recorder).
        return lignes.stream().map(AuditController::normaliser).toList();
    }

    /** Champs stables + JSON des détails rechargé. */
    private static Map<String, Object> normaliser(Map<String, Object> ligne) {
        Map<String, Object> sortie = new LinkedHashMap<>();
        sortie.put("date", String.valueOf(ligne.get("occurred_at")));
        sortie.put("acteur", ligne.get("actor_id") == null
                ? "anonyme" : String.valueOf(ligne.get("actor_id")));
        sortie.put("action", String.valueOf(ligne.get("action")));
        sortie.put("entite", String.valueOf(ligne.get("entity")));
        sortie.put("entiteId", ligne.get("entity_id") == null
                ? null : String.valueOf(ligne.get("entity_id")));
        sortie.put("structure", ligne.get("facility_id") == null
                ? null : String.valueOf(ligne.get("facility_id")));
        sortie.put("motif", ligne.get("reason") == null ? null : String.valueOf(ligne.get("reason")));
        sortie.put("resultat", String.valueOf(ligne.get("result")));
        sortie.put("details", String.valueOf(ligne.get("details")));
        return sortie;
    }
}
