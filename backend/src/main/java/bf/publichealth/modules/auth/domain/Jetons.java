package bf.publichealth.modules.auth.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Émission de JWT HS256 « à la main » — MAC + Base64URL, ZÉRO dépendance
 * nouvelle (même patron que JwtSecuriteIT, côté émission).
 *
 * <p>Claims émis : {@code sub} (UUID du compte), {@code app_role},
 * {@code nom}, {@code structure_id}, {@code patient_id} (jetons patient),
 * {@code iss} ({@code public-health-interne}), {@code iat}, {@code exp}.</p>
 *
 * <p>La vérification vit côté SecurityConfig (Nimbus, validation exp +
 * iss) : ce module ne fait qu'ÉMETTRE ; le secret est partagé par
 * configuration ({@code securite.jwt.secret}, ≥ 32 caractères).</p>
 */
public final class Jetons {

    /** Émetteur des jetons internes — validé si securite.jwt.issuer le reprend. */
    public static final String EMETTEUR = "public-health-interne";

    /** Durée de vie d'un jeton : 12 heures. */
    public static final long DUREE_SECONDES = 12 * 3600;

    private Jetons() {
    }

    /** Jeton d'un membre du personnel (rôle RoleUtilisateur). */
    public static String jetonStaff(UUID compteId, String role, String nom, UUID structureId,
                                    String secret) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", compteId.toString());
        claims.put("app_role", role);
        claims.put("nom", nom == null ? "" : nom);
        if (structureId != null) {
            claims.put("structure_id", structureId.toString());
        }
        return signer(claims, secret);
    }

    /** Jeton autoporteur d'un patient (rôle "patient", patient_id lié). */
    public static String jetonPatient(UUID patientId, String nom, String secret) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", patientId.toString());
        claims.put("app_role", "patient");
        claims.put("nom", nom == null ? "" : nom);
        claims.put("patient_id", patientId.toString());
        return signer(claims, secret);
    }

    // ------------------------------------------------------------------
    // Signature HS256
    // ------------------------------------------------------------------

    private static String signer(Map<String, Object> claims, String secret) {
        try {
            long maintenant = Instant.now().getEpochSecond();
            claims.put("iss", EMETTEUR);
            claims.put("iat", maintenant);
            claims.put("exp", maintenant + DUREE_SECONDES);

            String charge = base64Url(json(claims).getBytes(StandardCharsets.UTF_8));
            String entete = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8));
            String aSigner = entete + "." + charge;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(aSigner.getBytes(StandardCharsets.UTF_8));

            return aSigner + "." + base64Url(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Émission de jeton impossible", e);
        }
    }

    private static String json(Map<String, Object> claims) {
        StringBuilder json = new StringBuilder("{");
        boolean premier = true;
        for (Map.Entry<String, Object> claim : claims.entrySet()) {
            if (!premier) {
                json.append(',');
            }
            premier = false;
            json.append('"').append(claim.getKey()).append("\":");
            Object valeur = claim.getValue();
            if (valeur instanceof Number nombre) {
                // exp/iat sont des NumericDate : des NOMBRES JSON, jamais des chaînes
                // (Nimbus/Spring exigent un type numérique pour valider l'expiration).
                json.append(nombre);
            } else {
                json.append('"').append(String.valueOf(valeur)
                        .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String base64Url(byte[] octets) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }
}
