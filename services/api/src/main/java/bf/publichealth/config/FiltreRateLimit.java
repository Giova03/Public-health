package bf.publichealth.config;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate-limiting applicatif (épique E5, ADR-011 : sans Cloudflare, le
 * plan 2 porte le poids) — compartiment Bucket4j PAR IP sur
 * {@code /api/v1/**}, X-Forwarded-For respecté.
 *
 * <p>Défauts dans le code (application.yml intouchable) :
 * {@code securite.ratelimit.actif=true},
 * {@code securite.ratelimit.requests=600} requêtes par
 * {@code securite.ratelimit.fenetre-seconds=60}. Dépassement → 429
 * problem+json avec en-tête {@code Retry-After}, refus journalisé
 * (IP/endpoint — jamais de token).</p>
 *
 * <p>Le filtre court AVANT la chaîne Spring Security (ordre -150 &lt;
 * -100) : le quota protège l'entrée y compris contre les flots non
 * authentifiés. La carte des compartiments est locale au processus et
 * non bornée en P0 (recensement d'IP attendu : sites sanitaires du
 * pilote, ordre de grandeur ≪ 10^5) — à mutualiser sur Redis avec
 * l'épique montée en charge si le maillage national l'exige.</p>
 */
@Component
public class FiltreRateLimit extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(FiltreRateLimit.class);

    private final boolean actif;
    private final int requetes;
    private final int fenetreSeconds;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Bucket> compartiments = new ConcurrentHashMap<>();

    public FiltreRateLimit(
            @Value("${securite.ratelimit.actif:true}") boolean actif,
            @Value("${securite.ratelimit.requests:600}") int requetes,
            @Value("${securite.ratelimit.fenetre-seconds:60}") int fenetreSeconds,
            ObjectMapper objectMapper) {
        this.actif = actif;
        this.requetes = requetes;
        this.fenetreSeconds = fenetreSeconds;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain chaine) throws java.io.IOException, ServletException {
        if (!actif || !requete.getRequestURI().startsWith("/api/v1/")) {
            chaine.doFilter(requete, reponse);
            return;
        }
        String ip = IpCliente.resoudre(requete);
        Bucket compartiment = compartiments.computeIfAbsent(ip, this::nouveauCompartiment);
        if (compartiment.tryConsume(1)) {
            chaine.doFilter(requete, reponse);
            return;
        }
        refuser(requete, reponse, ip);
    }

    private void refuser(HttpServletRequest requete, HttpServletResponse reponse, String ip)
            throws java.io.IOException {
        // Repli conservateur : délai moyen entre deux jetons du compartiment.
        long retryApres = Math.max(1, (fenetreSeconds + requetes - 1) / Math.max(1, requetes));
        LOG.warn("REFUS 429 ip={} endpoint={} : quota de {} requêtes / {} s dépassé",
                ip, requete.getMethod() + " " + requete.getRequestURI(), requetes, fenetreSeconds);
        reponse.setHeader("Retry-After", String.valueOf(retryApres));
        ReponsesProblemDetail.ecrire(reponse, HttpStatus.TOO_MANY_REQUESTS,
                "Trop de requêtes",
                "Quota dépassé : " + requetes + " requêtes par " + fenetreSeconds
                        + " secondes — réessayer après " + retryApres + " s",
                objectMapper);
    }

    private Bucket nouveauCompartiment(String ip) {
        // Repli gourmand (greedy) : les jetons reviennent continûment sur la fenêtre.
        return Bucket.builder()
                .addLimit(limite -> limite
                        .capacity(requetes)
                        .refillGreedy(requetes, Duration.ofSeconds(fenetreSeconds)))
                .build();
    }

    /** Avant la chaîne Spring Security (ordre -100) : protection d'entrée. */
    @Override
    public int getOrder() {
        return -150;
    }
}
