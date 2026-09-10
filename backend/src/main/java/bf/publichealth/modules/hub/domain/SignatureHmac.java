package bf.publichealth.modules.hub.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature HMAC-SHA256 du protocole HUB — javax.crypto, aucune
 * dépendance ajoutée.
 *
 * <p>La signature porte sur les OCTETS exacts de l'enveloppe canonique
 * (UTF-8) ; le résultat est un hexadécimal minuscule (64 caractères),
 * transmis dans l'en-tête {@code X-PH-Signature} du transport.</p>
 *
 * <p>Règle absolue : le secret n'est JAMAIS journalisé, JAMAIS rendu par
 * une API — il vient de la configuration {@code hub.secret-destinations}
 * (défauts de développement dans le code, production par
 * propriété/environnement).</p>
 */
public final class SignatureHmac {

    private SignatureHmac() {
    }

    /**
     * Signe les octets canoniques avec le secret de la destination.
     *
     * @param octets les octets exacts de l'enveloppe canonique
     * @param secret secret partagé de la destination (jamais null)
     * @return hexadécimal minuscule du HMAC-SHA256
     */
    public static String signer(byte[] octets, String secret) {
        Objects.requireNonNull(octets, "octets manquants");
        Objects.requireNonNull(secret, "secret manquant");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(octets));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible : " + e.getMessage(), e);
        }
    }

    /**
     * Vérifie une signature attendue en comparaison à temps constant
     * (anti-chronométrage) — utilisé par le destinataire et par les tests.
     */
    public static boolean verifie(byte[] octets, String secret, String signatureAttendue) {
        if (signatureAttendue == null) {
            return false;
        }
        return MessageDigest.isEqual(
                signer(octets, secret).getBytes(StandardCharsets.UTF_8),
                signatureAttendue.trim().getBytes(StandardCharsets.UTF_8));
    }
}
