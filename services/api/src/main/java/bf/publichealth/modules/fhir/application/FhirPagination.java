package bf.publichealth.modules.fhir.application;

import bf.publichealth.modules.fhir.domain.ParametreFhirInvalideException;

/**
 * Pagination des Bundles searchset — {@code _count} (défaut 50, plafond 200)
 * et offset {@code page[offset]} (base 0).
 *
 * <p>P0 = pagination par offset (simple, suffisant pour le pilote) : le lien
 * {@code next} du Bundle porte l'offset de la page suivante. Les valeurs
 * absurdes sont refusées en 400 {@code invalid} ; le dépassement du plafond
 * est ramené à 200 (convention répandue, jamais une erreur).</p>
 *
 * @param count   taille de page effective (1..200)
 * @param decalage offset de la première entrée (>= 0)
 */
public record FhirPagination(int count, int decalage) {

    public static final int DEFAUT = 50;
    public static final int MAX = 200;

    public static FhirPagination depuis(String countBrut, String offsetBrut) {
        int count = entier(countBrut, "_count", DEFAUT);
        if (count < 1) {
            throw new ParametreFhirInvalideException("_count doit être un entier >= 1 (reçu : " + countBrut + ")");
        }
        if (count > MAX) {
            count = MAX; // plafonnement silencieux, documenté dans le CapabilityStatement
        }
        int decalage = entier(offsetBrut, "page[offset]", 0);
        if (decalage < 0) {
            throw new ParametreFhirInvalideException(
                    "page[offset] doit être un entier >= 0 (reçu : " + offsetBrut + ")");
        }
        return new FhirPagination(count, decalage);
    }

    private static int entier(String brut, String nom, int defaut) {
        if (brut == null || brut.isBlank()) {
            return defaut;
        }
        try {
            return Integer.parseInt(brut.trim());
        } catch (NumberFormatException e) {
            throw new ParametreFhirInvalideException(
                    nom + " doit être un entier (reçu : " + brut + ")");
        }
    }
}
