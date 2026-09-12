package bf.publichealth.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Sécurité HTTP — épique E5.
 *
 * <p>DEUX postures, pilotées par {@code securite.jwt.actif} (défaut
 * FALSE : la posture Sprint 0 — /actuator/health et /api/v1/** ouverts,
 * tout le reste refusé — est strictement conservée, le comportement
 * observable de l'API est inchangé, la suite existante reste verte sans
 * modification).</p>
 *
 * <p>Quand {@code securite.jwt.actif=true} (staging/production) :</p>
 * <ul>
 *   <li>serveur de ressources OAuth2 : vérification RÉELLE des JWT
 *       Supabase — RS256 via {@code securite.jwt.jwk-set-uri} (JWKS), ou
 *       HS256 via {@code securite.jwt.secret} (dev/test local) ; sans
 *       aucun des deux, le démarrage ÉCHOUE explicitement ;</li>
 *   <li>validation {@code exp} avec dérive d'horloge de 60 s (défaut des
 *       validateurs Nimbus) et {@code iss} si {@code securite.jwt.issuer}
 *       est renseigné ; claim {@code sub} = identité, rôle lu dans le
 *       claim {@code app_role} (chaîne) ou {@code roles} (chaîne ou
 *       tableau) → autorités {@code ROLE_<RÔLE>} (majuscules, convention
 *       Spring : {@code hasRole("ADMIN")} accepte les claims
 *       {@code admin} comme {@code ADMIN}) ;</li>
 *   <li>{@code /api/v1/**} ET {@code /fhir/**} exigent une
 *       authentification : 401 problem+json sans jeton, 403 si rôle
 *       insuffisant ({@code /api/v1/admin/**} requiert ROLE_ADMIN —
 *       convention P0, étendue par le RBAC E6). La sonde
 *       {@code /actuator/health/**} reste OUVERTE (contrat CI/infra).
 *       Le scraping Prometheus {@code /actuator/prometheus} (épique E8)
 *       exige une AUTHENTIFICATION — PAS un rôle : le scraper Grafana
 *       porte un JETON DE SERVICE lecture, pas un compte admin.
 *       L'uplink machine {@code /api/v1/sync} est authentifié comme le
 *       reste de l'API — AUCUNE exception métier en P0 (documenté : les
 *       clients machine portent un jeton de service) ;</li>
 *   <li>le refus est journalisé (utilisateur/IP/endpoint — JAMAIS de
 *       token ni de secret) au format problem+json (RFC 7807) ;</li>
 *   <li>{@link FiltreContexteRls} + {@link ControleRlsDataSource} posent
 *       la GUC {@code app.user_id} : les policies RLS de V10 mordent.</li>
 * </ul>
 *
 * <p>Épique E8 — observabilité : {@code /actuator/prometheus} suit la
 * posture du moment : OUVERT en posture Sprint 0 (JWT inactif — défaut,
 * le scraper local lit librement), AUTHENTIFIÉ quand
 * {@code securite.jwt.actif=true} (le scraper présentera un jeton de
 * service lecture ; pas de ROLE_ADMIN : un superviseur n'est pas un
 * administrateur fonctionnel). {@code /actuator/health/**} reste ouvert
 * dans les deux postures (contrat CI/répartiteur de charge).</p>
 *
 * <p>Les protections d'en-têtes restent inchangées et non négociables :
 * HSTS (31 536 000 s, sous-domaines inclus), X-Frame-Options DENY,
 * X-Content-Type-Options: nosniff (par défaut, jamais désactivé).</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private final boolean jwtActif;
    private final JwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            @Value("${securite.jwt.actif:false}") boolean jwtActif,
            @Value("${securite.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${securite.jwt.secret:}") String secret,
            @Value("${securite.jwt.issuer:}") String issuer,
            ObjectMapper objectMapper) {
        this.jwtActif = jwtActif;
        this.objectMapper = objectMapper;
        this.jwtDecoder = jwtActif ? construireDecoder(jwkSetUri, secret, issuer) : null;
    }

    // ------------------------------------------------------------------
    // Décodeur JWT — RS256 (JWKS Supabase) ou HS256 (secret local)
    // ------------------------------------------------------------------

    private static JwtDecoder construireDecoder(String jwkSetUri, String secret, String issuer) {
        NimbusJwtDecoder decodeur;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            decodeur = NimbusJwtDecoder.withJwkSetUri(jwkSetUri.trim()).build();
            LOG.info("JWT actif : vérification RS256 via JWKS {}", jwkSetUri.trim());
        } else if (secret != null && secret.isBlank()) {
            throw new IllegalStateException("securite.jwt.actif=true : ni securite.jwt.jwk-set-uri "
                    + "(RS256, JWKS Supabase) ni securite.jwt.secret (HS256, dev/test) n'est configuré "
                    + "— démarrage refusé");
        } else {
            // HS256 : Nimbus exige une clé d'au moins 256 bits (32 caractères).
            decodeur = NimbusJwtDecoder
                    .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            LOG.info("JWT actif : vérification HS256 par secret local (dev/test)");
        }
        // Validation : exp (dérive d'horloge 60 s, défaut Nimbus) + iss si renseigné.
        OAuth2TokenValidator<Jwt> validateur = issuer != null && !issuer.isBlank()
                ? JwtValidators.createDefaultWithIssuer(issuer.trim())
                : JwtValidators.createDefault();
        decodeur.setJwtValidator(validateur);
        return decodeur;
    }

    // ------------------------------------------------------------------
    // Chaîne de filtres
    // ------------------------------------------------------------------

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API sans état ; les webhooks sont signés HMAC
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))
                // Les protections par défaut restent actives, notamment
                // X-Content-Type-Options: nosniff — à ne JAMAIS désactiver.
                .authorizeHttpRequests(auth -> {
                    if (jwtActif) {
                        auth.requestMatchers("/api/v1/auth/**").permitAll()
                                // Webhook FedaPay : intégrité HMAC, PAS de jeton (P0).
                                .requestMatchers("/api/v1/webhooks/fedapay").permitAll()
                                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                                // Uplink machine /api/v1/sync : couvert par /api/v1/** —
                                // les clients machine portent un jeton de service (P0).
                                .requestMatchers("/api/v1/**", "/fhir/**").authenticated()
                                // Sonde d'infra : OUVERTE même JWT actif (contrat CI/LB).
                                .requestMatchers("/actuator/health/**").permitAll()
                                // Épique E8 : scraping Prometheus — AUTHENTIFIÉ, PAS
                                // ROLE_ADMIN : le scraper (Grafana Agent, Prometheus)
                                // présentera un JETON DE SERVICE lecture. Sans jeton → 401
                                // problem+json, comme le reste de l'API.
                                .requestMatchers("/actuator/prometheus").authenticated()
                                .anyRequest().denyAll();
                    } else {
                        // Posture Sprint 0 — inchangée (défaut).
                        // /fhir/** ouvert comme /api/v1/** en P0 (façade lecture ;
                        // verrouillage partenaire avec E6 — voir ADR-011).
                        // /actuator/prometheus (épique E8) : OUVERT dans cette
                        // posture — en production (JWT actif) le scraper présentera
                        // un jeton de service (matcher "authenticated" ci-dessus).
                        auth.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                                .requestMatchers("/actuator/prometheus").permitAll()
                                .requestMatchers("/api/v1/**", "/fhir/**").permitAll()
                                .anyRequest().denyAll();
                    }
                });

        if (jwtActif) {
            AuthenticationEntryPoint entree = pointEntree401();
            AccessDeniedHandler refus = gestionnaire403();
            http
                    .oauth2ResourceServer(resource -> resource
                            .jwt(jwt -> jwt.decoder(jwtDecoder)
                                    .jwtAuthenticationConverter(convertisseur()))
                            // 401 problem+json aussi pour les jetons invalides/expirés
                            // (BearerTokenAuthenticationFilter), pas seulement l'absence de jeton.
                            .authenticationEntryPoint(entree)
                            .accessDeniedHandler(refus))
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(entree)
                            .accessDeniedHandler(refus));
        }
        return http.build();
    }

    // ------------------------------------------------------------------
    // Identité et rôles : sub → principal, app_role/roles → ROLE_<rôle>
    // ------------------------------------------------------------------

    private static JwtAuthenticationConverter convertisseur() {
        JwtAuthenticationConverter convertisseur = new JwtAuthenticationConverter();
        convertisseur.setPrincipalClaimName("sub");
        convertisseur.setJwtGrantedAuthoritiesConverter(
                jeton -> (Collection<GrantedAuthority>) autorites(jeton));
        return convertisseur;
    }

    /**
     * Rôles du jeton : claim {@code app_role} (chaîne) puis {@code roles}
     * (chaîne ou tableau) — autorités {@code ROLE_<RÔLE>} en MAJUSCULES
     * (convention Spring : {@code hasRole("ADMIN")} reconnaît les claims
     * {@code admin} comme {@code ADMIN}).
     */
    private static List<GrantedAuthority> autorites(Jwt jeton) {
        Set<String> roles = new LinkedHashSet<>();
        Object roleApplication = jeton.getClaim("app_role");
        if (roleApplication instanceof String role && !role.isBlank()) {
            roles.add(role.trim());
        }
        Object rolesDeclares = jeton.getClaim("roles");
        if (rolesDeclares instanceof String role && !role.isBlank()) {
            roles.add(role.trim());
        } else if (rolesDeclares instanceof Collection<?> collection) {
            for (Object role : collection) {
                if (role != null && !String.valueOf(role).isBlank()) {
                    roles.add(String.valueOf(role).trim());
                }
            }
        }
        return roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(
                        "ROLE_" + r.toUpperCase(java.util.Locale.ROOT)))
                .toList();
    }

    // ------------------------------------------------------------------
    // 401 / 403 en problem+json — refus journalisés (user/IP/endpoint)
    // ------------------------------------------------------------------

    private AuthenticationEntryPoint pointEntree401() {
        return (requete, reponse, exception) -> {
            HttpServletRequest req = (HttpServletRequest) requete;
            LOG.warn("REFUS 401 ip={} endpoint={} : authentification JWT requise ({})",
                    IpCliente.resoudre(req), req.getMethod() + " " + req.getRequestURI(),
                    exception.getMessage());
            // Aucun indice exploitable : message d'échec générique, jamais le jeton.
            reponse.setHeader("WWW-Authenticate", "Bearer");
            ReponsesProblemDetail.ecrire(reponse, HttpStatus.UNAUTHORIZED,
                    "Authentification requise",
                    "Un jeton JWT valide (en-tête Authorization: Bearer) est obligatoire",
                    objectMapper);
        };
    }

    private AccessDeniedHandler gestionnaire403() {
        return (requete, reponse, exception) -> {
            HttpServletRequest req = (HttpServletRequest) requete;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String utilisateur = auth == null ? "-" : auth.getName();
            LOG.warn("REFUS 403 utilisateur={} ip={} endpoint={} : rôle insuffisant",
                    utilisateur, IpCliente.resoudre(req),
                    req.getMethod() + " " + req.getRequestURI());
            ReponsesProblemDetail.ecrire(reponse, HttpStatus.FORBIDDEN,
                    "Accès refusé",
                    "Le rôle de l'utilisateur ne permet pas d'accéder à cette ressource",
                    objectMapper);
        };
    }
}
