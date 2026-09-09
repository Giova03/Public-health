package bf.publichealth.modules.prescription.domain;

/**
 * Statut d'une prescription — append-only par conception (ADR épique E3).
 *
 * <p>Une prescription n'est JAMAIS réécrite ni détruite : elle est annulée
 * par contre-entrée explicite. Deux seules transitions légales, toutes
 * deux au départ d'ACTIVE, toutes deux terminales :</p>
 *
 * <pre>
 * active ──▶ cancelled          (annulation logistique, motif obligatoire)
 * active ──▶ entered-in-error   (erreur clinique de saisie, motif obligatoire)
 * </pre>
 *
 * <p>Le code bas-de-casse correspond aux valeurs CHECK de la migration V8 ;
 * la conversion JPA vit dans l'adaptateur de persistance.</p>
 */
public enum StatutPrescription {

    ACTIVE("active"),
    CANCELLED("cancelled"),
    ENTERED_IN_ERROR("entered-in-error");

    private final String code;

    StatutPrescription(String code) {
        this.code = code;
    }

    /** Valeur persistée en base (migration V8). */
    public String getCode() {
        return code;
    }

    /** Seul ACTIVE autorise la dispensation. */
    public boolean estActive() {
        return this == ACTIVE;
    }

    /**
     * Vérifie la légalité de l'annulation / contre-entrée, sinon lève
     * {@link IllegalPrescriptionTransitionException}. Une prescription
     * déjà annulée ou en erreur est un fait historique : il ne bouge plus.
     */
    public void exigerTransitionVers(StatutPrescription cible) {
        if (this != ACTIVE) {
            throw new IllegalPrescriptionTransitionException(this, cible);
        }
    }

    /** Résout le statut depuis la valeur persistée (base / SQL natif). */
    public static StatutPrescription depuisCode(String code) {
        for (StatutPrescription statut : values()) {
            if (statut.code.equals(code)) {
                return statut;
            }
        }
        throw new IllegalArgumentException("Statut de prescription inconnu : " + code);
    }
}
