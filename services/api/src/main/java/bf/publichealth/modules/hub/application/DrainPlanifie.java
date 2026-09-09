package bf.publichealth.modules.hub.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job planifié du connecteur HUB — drainage et envoi périodiques.
 *
 * <p>Cron : {@code hub.drain.cron} — défaut DANS LE CODE : toutes les
 * 30 secondes (expression « étoile-slash 30 » des six champs Spring).
 * Activation : {@code hub.drain.actif}, vrai par défaut (faux dans les
 * tests pour ne pas polluer les scénarios : ils appellent
 * {@code POST /api/v1/hub/drain} à la carte). Le job ne doit JAMAIS
 * tuer l'application : l'incident est crié.</p>
 */
@Component
@ConditionalOnProperty(name = "hub.drain.actif", havingValue = "true", matchIfMissing = true)
public class DrainPlanifie {

    private static final Logger LOG = LoggerFactory.getLogger(DrainPlanifie.class);

    private final HubService hubService;

    public DrainPlanifie(HubService hubService) {
        this.hubService = hubService;
    }

    @Scheduled(cron = "${hub.drain.cron:*/30 * * * * *}")
    public void passagePeriodique() {
        try {
            var rapport = hubService.drainManuel();
            if (rapport.messagesCrees() > 0 || rapport.envoyes() > 0
                    || !rapport.trousDetectes().isEmpty()) {
                LOG.info("Drainage HUB planifié : {} créés, {} envoyés, {} acquittés, {} morts, "
                                + "filigranes={}, trous={}",
                        rapport.messagesCrees(), rapport.envoyes(), rapport.acquittes(),
                        rapport.morts(), rapport.watermarkParDestination(),
                        rapport.trousDetectes().size());
            }
        } catch (Exception e) {
            // Le job ne doit jamais tuer l'application : l'incident est crié,
            // le prochain passage reprendra (tout est idempotent).
            LOG.error("ÉCHEC du drainage HUB planifié : {}", e.getMessage(), e);
        }
    }
}
