package bf.publichealth.modules.identity.adapter.persistence;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.publichealth.modules.identity.application.PhReferenceGenerator;

/**
 * Générateur PH-AAAA-NNNNNN sur séquence PostgreSQL — atomique : deux
 * créations concurrentes obtiennent deux numéros distincts, sans verrou
 * applicatif. L'année est celle du Burkina Faso (UTC+0).
 */
@Component
public class PhReferenceGeneratorPg implements PhReferenceGenerator {

    private final JdbcTemplate jdbc;

    public PhReferenceGeneratorPg(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextReference() {
        Long compteur = jdbc.queryForObject(
                "SELECT nextval('identity.patient_ref_seq')", Long.class);
        int annee = LocalDate.now(ZoneOffset.UTC).getYear();
        return "PH-%d-%06d".formatted(annee, compteur);
    }
}
