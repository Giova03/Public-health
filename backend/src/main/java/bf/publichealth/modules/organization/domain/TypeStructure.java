package bf.publichealth.modules.organization.domain;

/**
 * Type de structure sanitaire — pyramide sanitaire du Burkina Faso.
 *
 * <p>Codes bas-de-casse correspondant au CHECK de la migration V12 :
 * {@code csp} (Centre de Santé Primaire), {@code cs} (Centre de Santé),
 * {@code cm} (Centre Médical), {@code chu} (Centre Hospitalier
 * Universitaire), {@code chup} (Centre Hospitalier Universitaire
 * Pédiatrique), {@code cma} (Centre Médical avec Antenne chirurgicale),
 * {@code private} (clinique privée), {@code pharmacy} (officine). La
 * conversion JPA vit dans l'adaptateur de persistance.</p>
 */
public enum TypeStructure {

    CSP("csp"),
    CS("cs"),
    CM("cm"),
    CHU("chu"),
    CHUP("chup"),
    CMA("cma"),
    PRIVEE("private"),
    PHARMACIE("pharmacy");

    private final String code;

    TypeStructure(String code) {
        this.code = code;
    }

    /** Valeur persistée en base (migration V12). */
    public String getCode() {
        return code;
    }

    /** Résout le type depuis la valeur persistée (base / requête). */
    public static TypeStructure depuisCode(String code) {
        for (TypeStructure type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Type de structure inconnu : " + code + " (csp, cs, cm, chu, chup, cma, private, pharmacy)");
    }
}
