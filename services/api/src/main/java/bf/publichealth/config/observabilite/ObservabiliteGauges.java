package bf.publichealth.config.observabilite;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauges métier Prometheus — épique E8.
 *
 * <p>Un cycle (60 s par défaut, {@code observabilite.gauges.frequence-ms})
 * relit les compteurs d'exploitation par SELECT ciblés puis publie des
 * GAUGES dont la valeur est lue AU SCRAPE (le dernier snapshot est gardé
 * en mémoire : le scraping Prometheus ne touche jamais la base). Une
 * mesure indisponible vaut {@code NaN} + un WARN — la supervision ne doit
 * JAMAIS casser l'application. Activation :
 * {@code observabilite.gauges.actif} (vrai par défaut, défaut DANS LE CODE
 * — application.yml reste intouchable, contrainte d'intégration).</p>
 *
 * <p>Noms Prometheus exacts (dashboard Grafana — cf. docs/observabilite.md) :</p>
 * <ul>
 *   <li>{@code ph_sync_outbox_en_attente}, {@code ph_sync_outbox_retard_secondes}</li>
 *   <li>{@code ph_sync_ops{result="APPLIED|REJECTED|CONFLICT"}}</li>
 *   <li>{@code ph_paiements{statut="INITIATED|PENDING|AUTHORIZED|SUCCEEDED|FAILED|CANCELLED|REFUNDED|RECONCILED"}}</li>
 *   <li>{@code ph_identity_file_revue}</li>
 *   <li>{@code ph_reconciliation_derniere_age_secondes}</li>
 * </ul>
 *
 * <p><b>Contrainte de nommage</b> : le client Prometheus
 * ({@code PrometheusNaming.sanitizeMetricName}) réserve le suffixe
 * {@code _total} aux COMPTEURS et le RETIRE des gauges. Ces métriques sont
 * des gauges (les compteurs de statut de paiement varient à la baisse
 * quand les paiements transitionnent) : elles portent donc le nom scrappé
 * SANS suffixe — nom Micrometer = nom sur le wire, une seule vérité pour
 * Grafana (écart documenté dans docs/observabilite.md §3).</p>
 *
 * <p><b>Premier cycle</b> : {@link SmartInitializingSingleton} garantit que
 * la mesure initiale part APRÈS l'instanciation de tous les singletons —
 * donc après Flyway (les migrations ne sont pas encore posées quand les
 * composants sont construits, et la toute première mesure ne doit pas
 * produire une avalanche de « table absente »). Le cycle planifié prend
 * ensuite le relais toutes les 60 s.</p>
 *
 * <p>Le refresh manuel {@link #rafraichir()} est public : tests et
 * supervision déclenchent la mesure sans attendre le cron.</p>
 */
@Component
@ConditionalOnProperty(name = "observabilite.gauges.actif",
        havingValue = "true", matchIfMissing = true)
public class ObservabiliteGauges implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ObservabiliteGauges.class);

    public static final String M_OUTBOX_EN_ATTENTE = "ph_sync_outbox_en_attente";
    public static final String M_OUTBOX_RETARD = "ph_sync_outbox_retard_secondes";
    /** Sans « _total » : le client Prometheus réserve ce suffixe aux compteurs
     *  et le retire des gauges — on publie le nom scrappé EXACT. */
    public static final String M_OPS = "ph_sync_ops";
    /** Sans « _total » : idem (gauges, séries par statut). */
    public static final String M_PAIEMENTS = "ph_paiements";
    public static final String M_IDENTITY_FILE_REVUE = "ph_identity_file_revue";
    public static final String M_RECONCILIATION_AGE = "ph_reconciliation_derniere_age_secondes";

    /** Tag des séries d'uplink (valeurs du CHECK V4). */
    public static final String TAG_RESULTAT = "result";
    /** Tag des séries de paiement (valeurs du CHECK V2). */
    public static final String TAG_STATUT = "statut";

    private final SourceMesures source;
    private final MeterRegistry registre;

    /** Dernier snapshot publié — lu au scrape, jamais modifié pendant lecture. */
    private volatile SnapshotMesures dernier = SnapshotMesures.VIDE;

    /** Séries déjà enregistrées (cardinalité bornée par les CHECK V2/V4). */
    private final Set<String> resultatsEnregistres = ConcurrentHashMap.newKeySet();
    private final Set<String> statutsEnregistres = ConcurrentHashMap.newKeySet();

    public ObservabiliteGauges(SourceMesures source, MeterRegistry registre) {
        this.source = source;
        this.registre = registre;
        enregistrerGauges();
    }

    /**
     * Premier cycle — après l'instanciation de TOUS les singletons (donc
     * après Flyway) : les métriques sont numériques dès le premier scrape,
     * sans fenêtre NaN au démarrage.
     */
    @Override
    public void afterSingletonsInstantiated() {
        rafraichir();
    }

    /** Cycle planifié — 60 000 ms par défaut (observabilite.gauges.frequence-ms). */
    @Scheduled(fixedDelayString = "${observabilite.gauges.frequence-ms:60000}")
    public void passagePlanifie() {
        rafraichir();
    }

    /** Relit les mesures et republie l'état (appelable à la demande). */
    public void rafraichir() {
        SnapshotMesures mesures = source.mesurer();
        this.dernier = mesures;
        publierSeriesDynamiques(mesures);
        if (mesures.anomalies().isEmpty()) {
            LOG.debug("Gauges d'exploitation rafraîchies (outbox en attente : {})",
                    mesures.syncOutboxEnAttente());
        } else {
            LOG.warn("Gauges d'exploitation partiellement indisponibles : {}",
                    mesures.anomalies());
        }
    }

    // ------------------------------------------------------------------
    // Enregistrement des gauges (une fois) — valeur lue au scrape
    // ------------------------------------------------------------------

    private void enregistrerGauges() {
        Gauge.builder(M_OUTBOX_EN_ATTENTE, this,
                        g -> longOuNan(g.dernier.syncOutboxEnAttente()))
                .description("Événements de l'outbox transactionnel sync.outbox en attente "
                        + "de publication (published = false) — SLO : sortie < 5 min")
                .register(registre);
        Gauge.builder(M_OUTBOX_RETARD, this,
                        g -> doubleOuNan(g.dernier.syncOutboxRetardSecondes()))
                .description("Âge en secondes du plus ancien événement sync.outbox non publié "
                        + "— SLO : < 300 s ; alerte : > 1800 s")
                .register(registre);
        for (String resultat : SnapshotMesures.RESULTATS_SYNC_OP) {
            enregistrerGaugeResultat(resultat);
        }
        for (String statut : SnapshotMesures.STATUTS_PAIEMENT) {
            enregistrerGaugeStatut(statut);
        }
        Gauge.builder(M_IDENTITY_FILE_REVUE, this,
                        g -> longOuNan(g.dernier.identityFileRevue()))
                .description("Rapprochements identity.identity_match en attente de revue "
                        + "humaine (status = PENDING)")
                .register(registre);
        Gauge.builder(M_RECONCILIATION_AGE, this,
                        g -> doubleOuNan(g.dernier.reconciliationDerniereAgeSecondes()))
                .description("Âge en secondes du dernier payments.reconciliation_run terminé "
                        + "(finished_at) — SLO : réconciliation nocturne finie avant 06:00")
                .register(registre);
    }

    private void enregistrerGaugeResultat(String resultat) {
        resultatsEnregistres.add(resultat);
        Gauge.builder(M_OPS, this,
                        g -> longOuNan(g.dernier.syncOpsParResultat().get(resultat)))
                .tag(TAG_RESULTAT, resultat)
                .description("Cumul des opérations d'uplink sync.op par résultat "
                        + "(APPLIED/REJECTED/CONFLICT — CHECK V4)")
                .register(registre);
    }

    private void enregistrerGaugeStatut(String statut) {
        statutsEnregistres.add(statut);
        Gauge.builder(M_PAIEMENTS, this,
                        g -> longOuNan(g.dernier.paiementsParStatut().get(statut)))
                .tag(TAG_STATUT, statut)
                .description("Paiements payments.payment dans cet état (8 états "
                        + "forward-only, CHECK V2)")
                .register(registre);
    }

    /** Séries non prévues au boot (valeur ajoutée par une migration future). */
    private void publierSeriesDynamiques(SnapshotMesures mesures) {
        for (String resultat : mesures.syncOpsParResultat().keySet()) {
            if (resultatsEnregistres.add(resultat)) {
                enregistrerGaugeResultat(resultat);
                LOG.info("Nouvelle série de gauge publiée pour le résultat d'uplink {} "
                        + "(migration nouvelle ?)", resultat);
            }
        }
        for (String statut : mesures.paiementsParStatut().keySet()) {
            if (statutsEnregistres.add(statut)) {
                enregistrerGaugeStatut(statut);
                LOG.info("Nouvelle série de gauge publiée pour l'état de paiement {} "
                        + "(migration nouvelle ?)", statut);
            }
        }
    }

    private static double longOuNan(Long valeur) {
        return valeur == null ? Double.NaN : valeur;
    }

    private static double doubleOuNan(Double valeur) {
        return valeur == null ? Double.NaN : valeur;
    }
}
