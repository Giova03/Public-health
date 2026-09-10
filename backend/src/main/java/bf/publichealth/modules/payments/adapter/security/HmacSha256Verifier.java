package bf.publichealth.modules.payments.adapter.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import bf.publichealth.modules.payments.application.SignatureVerifier;

/**
 * HMAC-SHA256 sur la charge brute. Comparaison en temps constant
 * (MessageDigest.isEqual) — anti-chronométrage.
 *
 * <p>HYPOTHÈSE À VALIDER (épique E4) : format exact de l'en-tête de
 * signature FedaPay (hex simple ? préfixe « sha256= » ? horodaté ?).
 * L'implémentation tolère le préfixe courant et sera verrouillée
 * contre la documentation officielle.</p>
 */
@Component
public class HmacSha256Verifier implements SignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final byte[] secret;

    public HmacSha256Verifier(@Value("${fedapay.webhook-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean verify(String rawBody, String signature) {
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }
        String expected = hmacHex(rawBody);
        String provided = signature.trim();
        if (provided.startsWith("sha256=")) {
            provided = provided.substring("sha256=".length());
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }
}
