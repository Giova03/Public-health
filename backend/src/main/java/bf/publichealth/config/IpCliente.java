package bf.publichealth.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Résolution de l'IP cliente — {@code X-Forwarded-For} respecté (premier
 * saut, celui du client originel derrière le proxy/répartition de charge
 * national), repli sur l'adresse distante brute.
 *
 * <p>Sert aux journaux de refus (401/403/429) et à la clef de
 * compartiment du rate-limiting : IP uniquement, jamais de token ni de
 * secret dans les journaux.</p>
 */
public final class IpCliente {

    private IpCliente() {
    }

    /** Première valeur de X-Forwarded-For si présente, sinon l'adresse distante. */
    public static String resoudre(HttpServletRequest requete) {
        String transfere = requete.getHeader("X-Forwarded-For");
        if (transfere != null && !transfere.isBlank()) {
            String premierSaut = transfere.split(",")[0].trim();
            if (!premierSaut.isEmpty()) {
                return premierSaut;
            }
        }
        return requete.getRemoteAddr();
    }
}
