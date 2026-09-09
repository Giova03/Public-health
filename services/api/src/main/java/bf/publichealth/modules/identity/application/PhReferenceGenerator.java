package bf.publichealth.modules.identity.application;

/**
 * Générateur de la référence interne PH-AAAA-NNNNNN.
 *
 * <p>Port de sortie (application) — l'implémentation PostgreSQL s'appuie sur
 * une séquence atomique : deux créations concurrentes ne peuvent pas produire
 * la même référence, aucune table de verrou n'est nécessaire.</p>
 */
public interface PhReferenceGenerator {

    /**
     * @return la référence suivante, p. ex. « PH-2026-000123 »
     *         (année de création Burkina Faso = UTC+0, compteur 6 chiffres).
     */
    String nextReference();
}
