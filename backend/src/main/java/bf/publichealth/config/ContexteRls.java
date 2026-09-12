package bf.publichealth.config;

import java.util.UUID;

/**
 * Contexte RLS de la requête courante — ThreadLocal portant l'identité
 * (claim {@code sub} du JWT) et les rôles (claims {@code app_role}/{@code roles})
 * que le filtre {@link FiltreContexteRls} pose et que le
 * {@link ControleRlsDataSource} lit au moment de l'emprunt d'une connexion
 * pour poser les GUC PostgreSQL {@code app.user_id} et {@code app.roles}.
 *
 * <p>Valeurs uniques et bornées : un UUID et une liste de rôles en
 * minuscules (jamais un token, jamais un secret). Le ThreadLocal est
 * TOUJOURS effacé en fin de requête.</p>
 *
 * @param utilisateur identité de la requête (claim sub, UUID validé)
 * @param rolesCsv    rôles séparés par des virgules, minuscules —
 *                    {@code app.has_role()} de V5/V10 les compare en
 *                    minuscules (ex. {@code 'admin'}), chaîne vide si aucun
 */
record ContexteRls(UUID utilisateur, String rolesCsv) {

    private static final ThreadLocal<ContexteRls> CONTEXTE = new ThreadLocal<>();

    /** Pose l'identité et les rôles de la requête courante. */
    static void poser(UUID utilisateur, String rolesCsv) {
        CONTEXTE.set(new ContexteRls(utilisateur, rolesCsv == null ? "" : rolesCsv));
    }

    /** Contexte courant, null si la requête n'est pas authentifiée par JWT. */
    static ContexteRls courant() {
        return CONTEXTE.get();
    }

    /** Efface le ThreadLocal — appelé en fin de requête (finally). */
    static void effacer() {
        CONTEXTE.remove();
    }
}
