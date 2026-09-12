package bf.publichealth.modules.hub.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Destination du PH HUB : un partenaire national ou la simulation de
 * développement. Le <b>filigrane</b> ({@code watermark}) est le dernier
 * numéro de séquence <b>ACQUITTÉ</b> par la destination — la borne du
 * protocole, strictement monotone (elle n'avance que par
 * {@code GREATEST(watermark, séquence acquittée)}, jamais en arrière).
 *
 * <p>Les secrets HMAC ne vivent JAMAIS ici : la table ne possède aucune
 * colonne secret — ils viennent de la configuration
 * {@code hub.secret-destinations} (défauts dans le code, production par
 * propriété/environnement), jamais de la base, jamais des réponses API.</p>
 *
 * @param id        identifiant
 * @param code      code unique, ex {@code ph-hub-national}, {@code ph-hub-simulation}
 * @param baseUrl   URL de base du point de sortie (HTTPS 443 en production)
 * @param actif     destination drainée et poussée ou non
 * @param watermark dernier numéro de séquence acquitté par la destination (≥ 0)
 */
public record Destination(UUID id, String code, String baseUrl, boolean actif,
                          long watermark, Instant createdAt) {

    /** Création d'une destination (watermark initial 0). */
    public Destination {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Le code de destination HUB est obligatoire");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("L'URL de base de la destination HUB est obligatoire");
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            // La simulation de dev vit en http ; la production est HTTPS 443 (ADR).
            throw new IllegalArgumentException(
                    "L'URL de base de la destination HUB doit être http(s) : " + baseUrl);
        }
        if (watermark < 0) {
            throw new IllegalArgumentException("Le filigrane ne peut pas être négatif : " + watermark);
        }
    }
}
