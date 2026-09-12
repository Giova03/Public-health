package bf.publichealth.modules.administration.domain;

/**
 * Rôle applicatif d'un utilisateur — matrice RBAC de l'épique E6.
 *
 * <p>Codes bas-de-casse correspondant au CHECK de la migration V12.
 * La cartographie rôle → permissions vit dans {@link RolesPermissions}
 * (domaine pur) et son miroir {@code administration.role_permission}
 * (semé à l'identique par V12, contrôlé par BackofficeIT).</p>
 *
 * <p>Au niveau HTTP (E5), le claim {@code app_role}/{@code roles} du
 * JWT Supabase devient {@code ROLE_<RÔLE>} : {@code admin} suffit pour
 * /api/v1/admin/** — le RBAC fin (permissions) arrive avec E6.</p>
 */
public enum RoleUtilisateur {

    ADMIN("admin"),
    MEDECIN("medecin"),
    INFIRMIER("infirmier"),
    PHARMACIEN("pharmacien"),
    AGENT_FINANCIER("agent_financier"),
    SUPERVISEUR("superviseur"),
    /** Rôle ajouté par V14 (audit I2/I12) : admission MPI uniquement. */
    AGENT_SAISIE("agent_saisie");

    private final String code;

    RoleUtilisateur(String code) {
        this.code = code;
    }

    /** Valeur persistée en base (migrations V12/V14) et portée par le JWT. */
    public String getCode() {
        return code;
    }

    /** Résout le rôle depuis la valeur persistée (base / requête / claim). */
    public static RoleUtilisateur depuisCode(String code) {
        for (RoleUtilisateur role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException(
                "Rôle inconnu : " + code + " (admin, medecin, infirmier, pharmacien, agent_financier, "
                        + "superviseur, agent_saisie)");
    }

    /** Rôle patient — n'est PAS un RoleUtilisateur : jeton autoporteur patient_id. */
    public static final String ROLE_PATIENT = "patient";
}
