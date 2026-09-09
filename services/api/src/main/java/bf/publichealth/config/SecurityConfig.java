package bf.publichealth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sprint 0 : posture explicite et temporaire.
 * /actuator/health et /api/v1/** sont ouverts (le webhook doit être joignable).
 * Tout le reste est REFUSÉ (denyAll), pas silencieusement permis.
 *
 * <p>TODO épiques E5/E6 : verrouillage complet — JWT Supabase vérifiés,
 * RBAC × ABAC, rate-limiting Bucket4j (ADR-011 : sans Cloudflare, le plan 2
 * applicatif porte le poids), en-tête de session app.* pour la RLS.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // API sans état ; les webhooks sont signés HMAC
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/**").permitAll() // TODO E5/E6 : exiger JWT
                        .anyRequest().denyAll())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)));
        // Les protections par défaut restent actives, notamment
        // X-Content-Type-Options: nosniff — à ne JAMAIS désactiver.
        return http.build();
    }
}
