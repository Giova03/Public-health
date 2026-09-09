package bf.publichealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PUBLIC HEALTH — monolithe modulaire (ADR-001).
 *
 * <p>15 modules, chacun suivant l'architecture hexagonale :
 * domaine (pur, sans dépendance framework) / application (cas d'usage)
 * / adaptateurs entrants (REST) et sortants (persistance, FHIR, PSP).</p>
 *
 * <p>Cinq lois inter-modules, non négociables :
 * un module = un schéma PostgreSQL ; communication par interface ou
 * événement (outbox) ; jointure SQL inter-schémas interdite ; seuls
 * fhir-adapter et hub parlent « étranger » ; contrats stables, DTO immuables.</p>
 */
@SpringBootApplication
public class PublicHealthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublicHealthApplication.class, args);
    }
}
