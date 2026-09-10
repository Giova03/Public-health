package bf.publichealth.modules.payments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job nocturne de réconciliation — LA source de vérité (ADR-006).
 *
 * <p>Cron configurable : {@code paiements.reconciliation.cron} (défaut
 * {@code 0 15 3 * * *} — 03:15, heure creuse). Activation :
 * {@code paiements.reconciliation.active} (vrai par défaut ; faux en
 * environnement de test). Le déclenchement MANUEL (tests, supervision)
 * passe par POST /api/v1/reconciliation/run, qui rend le rapport.</p>
 */
@Component
@ConditionalOnProperty(name = "paiements.reconciliation.active",
        havingValue = "true", matchIfMissing = true)
public class ReconciliationNocturne {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationNocturne.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationNocturne(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${paiements.reconciliation.cron:0 15 3 * * *}")
    public void passageNocturne() {
        try {
            var rapport = reconciliationService.executer("schedule");
            LOG.info("Réconciliation nocturne terminée : {}", rapport.counts());
        } catch (Exception e) {
            // Le job ne doit jamais tuer l'application : l'incident est crié.
            LOG.error("ÉCHEC de la réconciliation nocturne : {}", e.getMessage(), e);
        }
    }
}
