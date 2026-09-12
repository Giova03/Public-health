package bf.publichealth.modules.statistiques.adapter.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API statistiques SNIS — /api/v1/statistiques/snis (V14, I11).
 *
 * <p>La motivation d'un système NATIONAL est le SNIS/DHIS2 : chaque
 * mois, l'ICP agrège son registre, le district consolide, la région
 * consolide, le Ministère décide. Ce endpoint produit les AGREGATS
 * mensuels d'une structure à partir des données individuelles :
 * consultations par tranche d'âge et sexe, paludisme confirmé (TDR+),
 * diagnostics principaux, ordonnances, dispensations, paiements,
 * références (dont non abouties), décès. Export CSV intégré.</p>
 *
 * <p>Permission : audit:lire (superviseur, admin) — le SNIS est la
 * donnée de supervision par excellence (I11 : la donnée remonte
 * ENFIN).</p>
 */
@RestController
@RequestMapping("/api/v1/statistiques")
public class StatistiquesController {

    private final JdbcTemplate jdbc;

    public StatistiquesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/snis")
    public ResponseEntity<Map<String, Object>> snis(
            @RequestParam UUID structureId,
            @RequestParam(required = false) String mois) {
        YearMonth periode = mois == null || mois.isBlank()
                ? YearMonth.now() : YearMonth.parse(mois);
        LocalDate debut = periode.atDay(1);
        LocalDate fin = periode.plusMonths(1).atDay(1);
        return ResponseEntity.ok(agregats(structureId, debut, fin, periode.toString()));
    }

    @GetMapping("/snis/export.csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam UUID structureId,
            @RequestParam(required = false) String mois) {
        YearMonth periode = mois == null || mois.isBlank()
                ? YearMonth.now() : YearMonth.parse(mois);
        Map<String, Object> agregats = agregats(structureId, periode.atDay(1),
                periode.plusMonths(1).atDay(1), periode.toString());

        StringBuilder csv = new StringBuilder();
        csv.append("indicateur;valeur\n");
        csv.append("structure;").append(structureId).append('\n');
        csv.append("periode;").append(periode).append('\n');
        @SuppressWarnings("unchecked")
        Map<String, Object> consultations = (Map<String, Object>) agregats.get("consultations");
        consultations.forEach((cle, valeur) -> csv.append("consultations_").append(cle)
                .append(';').append(valeur).append('\n'));
        @SuppressWarnings("unchecked")
        Map<String, Object> paiements = (Map<String, Object>) agregats.get("paiements");
        paiements.forEach((cle, valeur) -> csv.append("paiements_").append(cle)
                .append(';').append(valeur).append('\n'));
        @SuppressWarnings("unchecked")
        Map<String, Object> references = (Map<String, Object>) agregats.get("references");
        references.forEach((cle, valeur) -> csv.append("references_").append(cle)
                .append(';').append(valeur).append('\n'));
        ((List<?>) agregats.get("diagnostics")).forEach(ligne -> {
            Map<?, ?> diag = (Map<?, ?>) ligne;
            csv.append("diagnostic_").append(diag.get("code")).append(';')
                    .append(diag.get("total")).append('\n');
        });
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition",
                        "attachment; filename=snis_" + structureId + "_" + periode + ".csv")
                .body(csv.toString());
    }

    // ------------------------------------------------------------------
    // Agrégation — SQL direct sur les tables individuelles
    // ------------------------------------------------------------------

    private Map<String, Object> agregats(UUID structureId, LocalDate debut, LocalDate fin,
                                        String periode) {
        Map<String, Object> sortie = new LinkedHashMap<>();
        sortie.put("structureId", structureId.toString());
        sortie.put("periode", periode);

        // Consultations par tranche d'âge et sexe (tranches SNIS).
        Map<String, Object> consultations = new LinkedHashMap<>();
        consultations.put("total", compter("""
                SELECT count(*) FROM clinical.encounter e
                 WHERE e.facility_id = ? AND e.encounter_class = 'consultation'
                   AND e.started_at >= ? AND e.started_at < ?
                """, structureId, debut, fin));
        consultations.put("moins_de_5_ans", compter("""
                SELECT count(*) FROM clinical.encounter e
                 JOIN identity.patient p ON p.id = e.patient_id
                 WHERE e.facility_id = ? AND e.encounter_class = 'consultation'
                   AND e.started_at >= ? AND e.started_at < ?
                   AND p.birth_date > ?::date - interval '5 years'
                """, structureId, debut, fin, fin));
        consultations.put("5_a_14_ans", compter("""
                SELECT count(*) FROM clinical.encounter e
                 JOIN identity.patient p ON p.id = e.patient_id
                 WHERE e.facility_id = ? AND e.encounter_class = 'consultation'
                   AND e.started_at >= ? AND e.started_at < ?
                   AND p.birth_date <= ?::date - interval '5 years'
                   AND p.birth_date > ?::date - interval '15 years'
                """, structureId, debut, fin, fin, fin));
        consultations.put("femmes_15_49", compter("""
                SELECT count(*) FROM clinical.encounter e
                 JOIN identity.patient p ON p.id = e.patient_id
                 WHERE e.facility_id = ? AND e.encounter_class = 'consultation'
                   AND e.started_at >= ? AND e.started_at < ?
                   AND p.gender = 'female'
                   AND p.birth_date <= ?::date - interval '15 years'
                   AND p.birth_date > ?::date - interval '50 years'
                """, structureId, debut, fin, fin, fin));
        sortie.put("consultations", consultations);

        // Paludisme confirmé : TDR/goutte épaisse positifs (la PREUVE, pas l'opinion).
        sortie.put("paludismeConfirme", compter("""
                SELECT count(*) FROM laboratoire.examen
                 WHERE structure_id = ? AND statut = 'resultat'
                   AND resultat_positif = true
                   AND type IN ('tdr_paludisme','goutte_epaisse')
                   AND demande_le >= ? AND demande_le < ?
                """, structureId, debut, fin));

        // Diagnostics principaux (conditions codées).
        List<Map<String, Object>> diagnostics = jdbc.queryForList("""
                SELECT c.code, count(*) AS total
                  FROM clinical.condition c
                  JOIN clinical.encounter e ON e.id = c.encounter_id
                 WHERE e.facility_id = ? AND e.encounter_class = 'consultation'
                   AND e.started_at >= ? AND e.started_at < ?
                 GROUP BY c.code ORDER BY total DESC LIMIT 15
                """, structureId, debut, fin);
        sortie.put("diagnostics", diagnostics);

        // Ordonnances et dispensations.
        sortie.put("ordonnances", compter("""
                SELECT count(*) FROM prescription.prescription
                 WHERE facility_id = ? AND issued_at >= ? AND issued_at < ?
                """, structureId, debut, fin));
        sortie.put("dispensations", compter("""
                SELECT count(*) FROM prescription.dispensation d
                  JOIN prescription.prescription p ON p.id = d.prescription_id
                 WHERE p.facility_id = ? AND d.dispensed_at >= ? AND d.dispensed_at < ?
                """, structureId, debut, fin));

        // Paiements (montants XOF) — la traçabilité structure passe par
        // la facture puis l'encounter ; les paiements sans encounter
        // (frais d'accès purs) sont comptés à part (non attribuables).
        Map<String, Object> paiements = new LinkedHashMap<>();
        paiements.put("inities", compter("""
                SELECT count(*) FROM payments.payment pay
                  JOIN payments.invoice inv ON inv.id = pay.invoice_id
                  LEFT JOIN clinical.encounter enc ON enc.id = inv.encounter_id
                 WHERE pay.created_at >= ? AND pay.created_at < ?
                   AND (enc.facility_id = ? OR inv.encounter_id IS NULL)
                """, debut, fin, structureId));
        Double encaisse = jdbc.queryForObject("""
                SELECT COALESCE(sum(pay.amount), 0) FROM payments.payment pay
                  JOIN payments.invoice inv ON inv.id = pay.invoice_id
                  LEFT JOIN clinical.encounter enc ON enc.id = inv.encounter_id
                 WHERE pay.created_at >= ? AND pay.created_at < ?
                   AND (enc.facility_id = ? OR inv.encounter_id IS NULL)
                   AND pay.state = 'SUCCEEDED'
                """, Double.class, debut, fin, structureId);
        paiements.put("encaisseXOF", encaisse == null ? 0 : encaisse.intValue());
        paiements.put("echecs", compter("""
                SELECT count(*) FROM payments.payment pay
                  JOIN payments.invoice inv ON inv.id = pay.invoice_id
                  LEFT JOIN clinical.encounter enc ON enc.id = inv.encounter_id
                 WHERE pay.created_at >= ? AND pay.created_at < ?
                   AND (enc.facility_id = ? OR inv.encounter_id IS NULL)
                   AND pay.state = 'FAILED'
                """, debut, fin, structureId));
        sortie.put("paiements", paiements);

        // Références : sorties, abouties, NON abouties (l'indicateur qui manque).
        Map<String, Object> references = new LinkedHashMap<>();
        references.put("envoyees", compter("""
                SELECT count(*) FROM reference.fiche
                 WHERE structure_origine = ? AND created_at >= ? AND created_at < ?
                """, structureId, debut, fin));
        Long nonAbouties = jdbc.queryForObject("""
                SELECT count(*) FROM reference.fiche
                 WHERE structure_origine = ? AND statut = 'envoyee'
                   AND created_at < now() - interval '48 hours'
                """, Long.class, structureId);
        references.put("nonAbouties48h", nonAbouties == null ? 0 : nonAbouties);
        sortie.put("references", references);

        // Rendez-vous.
        Map<String, Object> rdv = new LinkedHashMap<>();
        rdv.put("demandes", compter("""
                SELECT count(*) FROM rendezvous.rendez_vous
                 WHERE structure_id = ? AND created_at >= ? AND created_at < ?
                """, structureId, debut, fin));
        rdv.put("honores", compter("""
                SELECT count(*) FROM rendezvous.rendez_vous
                 WHERE structure_id = ? AND statut = 'honore'
                   AND created_at >= ? AND created_at < ?
                """, structureId, debut, fin));
        rdv.put("annules", compter("""
                SELECT count(*) FROM rendezvous.rendez_vous
                 WHERE structure_id = ? AND statut = 'annule'
                   AND created_at >= ? AND created_at < ?
                """, structureId, debut, fin));
        sortie.put("rendezVous", rdv);

        // Décès déclarés (rapport de mortalité).
        sortie.put("deces", compter("""
                SELECT count(*) FROM identity.patient
                 WHERE deceased = true AND deceased_at >= ? AND deceased_at < ?
                """, debut, fin));

        // ruptures de stock actuelles.
        Long ruptures = jdbc.queryForObject("""
                SELECT count(*) FROM pharmacie.stock_item
                 WHERE structure_id = ? AND quantite <= 0
                """, Long.class, structureId);
        sortie.put("rupturesStock", ruptures == null ? 0 : ruptures);

        return sortie;
    }

    private long compter(String sql, Object... parametres) {
        Long total = jdbc.queryForObject(sql, Long.class, parametres);
        return total == null ? 0 : total;
    }
}
