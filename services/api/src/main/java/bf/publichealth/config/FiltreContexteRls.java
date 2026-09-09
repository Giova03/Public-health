package bf.publichealth.config;

import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtre d'en-tête de session RLS (épique E5) : quand un JWT est validé
 * par la chaîne Spring Security, le claim {@code sub} (UUID) et les rôles
 * (autorités {@code ROLE_<x>}) deviennent le contexte {@link ContexteRls}
 * de la requête — le {@link ControleRlsDataSource} les traduira en GUC
 * PostgreSQL {@code app.user_id} et {@code app.roles} pour que les
 * policies RLS de V5/V10 mordent.
 *
 * <p>Un sub non-UUID ne pose AUCUNE GUC (RLS fail-closed : app_rw ne voit
 * alors rien) — refus silencieux consigné. Les rôles partent en
 * MINUSCULES : les policies V5/V10 comparent {@code app.has_role} en
 * minuscules ({@code 'admin'}). Le ThreadLocal est toujours effacé en fin
 * de requête.</p>
 */
@Component
public class FiltreContexteRls extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FiltreContexteRls.class);

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain chaine) throws java.io.IOException, ServletException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jeton) {
                String sujet = jeton.getToken().getSubject();
                if (sujet != null) {
                    try {
                        ContexteRls.poser(UUID.fromString(sujet), rolesCsv(jeton));
                    } catch (IllegalArgumentException e) {
                        LOG.warn("sub JWT non-UUID (len={}) : GUC app.user_id non posée, "
                                + "la RLS restera fail-closed pour app_rw",
                                sujet.length());
                    }
                }
            }
            chaine.doFilter(requete, reponse);
        } finally {
            ContexteRls.effacer();
        }
    }

    /** Rôles de la requête en CSV minuscules — les policies lisent 'admin'. */
    private static String rolesCsv(Authentication auth) {
        StringBuilder csv = new StringBuilder();
        for (GrantedAuthority autorite : auth.getAuthorities()) {
            String nom = autorite.getAuthority();
            if (nom != null && nom.regionMatches(true, 0, "ROLE_", 0, 5)) {
                nom = nom.substring(5);
            }
            if (nom == null || nom.isBlank()) {
                continue;
            }
            if (!csv.isEmpty()) {
                csv.append(',');
            }
            csv.append(nom.trim().toLowerCase(Locale.ROOT));
        }
        return csv.toString();
    }
}
