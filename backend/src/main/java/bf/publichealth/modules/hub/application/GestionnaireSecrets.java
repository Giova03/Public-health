package bf.publichealth.modules.hub.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gestionnaire des secrets HMAC des destinations — code → secret partagé.
 *
 * <p>Les secrets ne vivent JAMAIS en base (hub.destination n'a aucune
 * colonne secret) et JAMAIS dans application.yml : la map initiale vient
 * de la propriété {@code hub.secret-destinations} avec, PAR DÉFAUT DANS
 * LE CODE, la destination de développement
 * {@code ph-hub-simulation} → {@value #SECRET_DEV} (documenté, à remplacer
 * en production par propriété ou variable d'environnement :
 * {@code hub.secret-destinations={ph-hub-national:'…',autre:'…'}}).</p>
 *
 * <p>La création d'une destination par API peut enregistrer un secret
 * RUNTIME (volatile : perdu au redémarrage — en production, la
 * configuration prime). Le secret n'est JOURNALISÉ et RENDU nulle part :
 * {@link #secretPour(String)} est la seule fenêtre, réservée à la
 * signature.</p>
 */
public class GestionnaireSecrets {

    private static final Logger LOG = LoggerFactory.getLogger(GestionnaireSecrets.class);

    /** Secret de développement, documenté — JAMAIS en production. */
    public static final String SECRET_DEV = "dev-secret-hub-a-changer";

    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    public GestionnaireSecrets(Map<String, String> secretsInitiaux) {
        this.secrets.putAll(secretsInitiaux);
    }

    /**
     * Secret de la destination : celui de la configuration si présent,
     * sinon le secret de développement (journalisé UNE fois par code,
     * sans jamais révéler la valeur).
     */
    public String secretPour(String codeDestination) {
        String secret = secrets.get(codeDestination);
        if (secret != null && !secret.isBlank()) {
            return secret;
        }
        LOG.warn("Destination {} sans secret dédié : secret de développement utilisé "
                + "(à remplacer en production via hub.secret-destinations)", codeDestination);
        return SECRET_DEV;
    }

    /** Enregistre un secret runtime (création de destination par API). */
    public void enregistrer(String codeDestination, String secret) {
        if (codeDestination == null || codeDestination.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Code ou secret de destination manquant");
        }
        secrets.put(codeDestination, secret);
    }

    /** Un secret dédié est-il configuré pour ce code ? */
    public boolean aSecretDedie(String codeDestination) {
        return secrets.containsKey(codeDestination);
    }
}
