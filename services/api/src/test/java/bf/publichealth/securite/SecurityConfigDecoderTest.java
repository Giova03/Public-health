package bf.publichealth.securite;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.config.SecurityConfig;

/**
 * Construction du décodeur JWT — épique E5 (unitaire, sans contexte).
 *
 * <p>Posture par défaut : {@code securite.jwt.actif=false} → AUCUN
 * décodeur construit (la vérification JWT n'existe pas, comportement
 * Sprint 0 inchangé). Quand le flag est actif : JWKS (RS256) ou secret
 * (HS256) ; ni l'un ni l'autre → échec de démarrage EXPLICITE
 * (IllegalStateException, message actionnable) — jamais un mode
 * silencieusement ouvert.</p>
 */
class SecurityConfigDecoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("jwt inactif (défaut) : aucun décodeur construit, posture Sprint 0")
    void jwtInactifNeConstruitRien() {
        assertThatCode(() -> new SecurityConfig(false, "", "un-secret", "un-issuer", MAPPER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("jwt actif sans JWKS ni secret : démarrage refusé explicitement")
    void jwtActifSansConfigurationRefuse() {
        assertThatThrownBy(() -> new SecurityConfig(true, "", "", "", MAPPER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("securite.jwt.actif=true")
                .hasMessageContaining("jwk-set-uri")
                .hasMessageContaining("securite.jwt.secret")
                .hasMessageContaining("démarrage refusé");
    }

    @Test
    @DisplayName("jwt actif avec secret HS256 (≥ 256 bits) : constructible")
    void jwtActifAvecSecretConstruit() {
        assertThatCode(() -> new SecurityConfig(true, "",
                "secret-hs256-de-test-public-health-0123456789", "", MAPPER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("jwt actif avec JWKS : constructible (RS256 Supabase)")
    void jwtActifAvecJwksConstruit() {
        assertThatCode(() -> new SecurityConfig(true,
                "https://exemple.supabase.co/auth/v1/.well-known/jwks.json", "", "", MAPPER))
                .doesNotThrowAnyException();
    }
}
