package bf.publichealth.common;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/meta — carte de la plateforme.
 *
 * <p>Exposé pour la supervision et les tests de fumée : l'API déclare
 * elle-même ses 15 modules (ADR-001, monolithe modulaire). Un désaccord
 * entre cette liste et le code est un incident de conception.</p>
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    public record MetaResponse(String application, String version, String sprint,
                               List<String> modules, List<String> reglesStructurelles) {
    }

    /** Les 15 modules du monolithe modulaire (paiements et audit déjà livrés). */
    private static final List<String> MODULES = List.of(
            "patient", "identity", "organization", "appointment",
            "encounter", "consultation", "prescription", "pharmacy",
            "payments", "notification", "audit", "administration",
            "sync", "fhir", "hub");

    private static final List<String> REGLES = List.of(
            "un module = un schéma PostgreSQL",
            "communications inter-modules par interfaces ou événements uniquement",
            "aucune clé étrangère ni JOIN inter-schémas",
            "seuls fhir et hub parlent « étranger » (FHIR, HUB)",
            "les DTO d'adaptateurs sont des contrats stables");

    private final String applicationName;

    public MetaController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping
    public MetaResponse meta() {
        return new MetaResponse(applicationName, "0.1.0-SNAPSHOT", "Sprint 0 — socle",
                MODULES, REGLES);
    }
}
