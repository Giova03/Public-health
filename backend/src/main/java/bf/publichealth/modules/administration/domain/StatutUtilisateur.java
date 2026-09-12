package bf.publichealth.modules.administration.domain;

/**
 * Statut d'un compte utilisateur du back-office — cycle de vie
 * administratif de l'épique E6.
 *
 * <pre>
 * invite ──activation (liaison Supabase)──▶ actif
 * actif  ──suspension (motif obligatoire)─▶ suspendu
 * suspendu ──réactivation─────────────────▶ actif
 * </pre>
 *
 * <p>Il n'existe AUCUNE sortie terminal : un utilisateur ne se
 * supprime JAMAIS (garde SQL V12), la suspension est la seule issue —
 * l'historique administratif est append-only. Le code bas-de-casse
 * correspond au CHECK de la migration V12 ; la conversion JPA vit
 * dans l'adaptateur de persistance.</p>
 */
public enum StatutUtilisateur {

    INVITE("invite"),
    ACTIF("actif"),
    SUSPENDU("suspendu");

    private final String code;

    StatutUtilisateur(String code) {
        this.code = code;
    }

    /** Valeur persistée en base (migration V12). */
    public String getCode() {
        return code;
    }

    /** Seul un compte ACTIF porte des permissions effectives (fail-closed). */
    public boolean estActif() {
        return this == ACTIF;
    }

    /**
     * Vérifie la légalité de la transition, sinon lève
     * {@link IllegalUtilisateurTransitionException} — un refus vaut
     * 409 et une trace d'audit, jamais de double changement en silence.
     */
    public void exigerTransitionVers(StatutUtilisateur cible) {
        boolean legale = (this == INVITE && cible == ACTIF)
                || (this == ACTIF && cible == SUSPENDU)
                || (this == SUSPENDU && cible == ACTIF);
        if (!legale) {
            throw new IllegalUtilisateurTransitionException(this, cible);
        }
    }

    /** Résout le statut depuis la valeur persistée (base / SQL natif). */
    public static StatutUtilisateur depuisCode(String code) {
        for (StatutUtilisateur statut : values()) {
            if (statut.code.equals(code)) {
                return statut;
            }
        }
        throw new IllegalArgumentException("Statut d'utilisateur inconnu : " + code);
    }
}
