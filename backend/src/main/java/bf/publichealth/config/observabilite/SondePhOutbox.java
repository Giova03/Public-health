package bf.publichealth.config.observabilite;

import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Sonde {@code phOutbox} — épique E8 : la sortie de l'outbox transactionnel
 * ne doit pas traîner.
 *
 * <p>UP si en attente {@code < 1000} ET retard {@code < 3600 s} ; DOWN sinon
 * (détail : valeurs + seuils). Si la mesure est indisponible (table absente
 * — futur schéma, erreur SQL), la sonde dégrade en UNKNOWN avec le détail :
 * JAMAIS DOWN par erreur de requête (la santé de la base elle-même est
 * portée par {@code phBase}).</p>
 *
 * <p>Cohérence SLO (docs/observabilite.md) : objectif 5 min, alerte
 * exploitation à 30 min, DOWN de la sonde au-delà de 60 min (le retard
 * cumulé finit par vider l'agrégat DOWN → l'alerte « health DOWN »).</p>
 */
public class SondePhOutbox implements HealthIndicator {

    /** Seuil de volume : au-delà, DOWN (accumulation anormale). */
    public static final long SEUIL_EN_ATTENTE = 1_000;

    /** Seuil de retard : au-delà, DOWN (1 h > alerte 30 min : rouge franc). */
    public static final double SEUIL_RETARD_SECONDES = 3_600;

    private static final String SLO =
            "la sortie de l'outbox ne doit pas traîner (SLO : < 5 min ; alerte : > 30 min)";

    private final SourceMesures source;

    public SondePhOutbox(SourceMesures source) {
        this.source = source;
    }

    @Override
    public Health health() {
        try {
            SnapshotMesures mesures = source.mesurer();
            if (mesures.syncOutboxEnAttente() == null || mesures.syncOutboxRetardSecondes() == null) {
                // Table absente (42P01) ou erreur SQL : UNKNOWN, jamais DOWN.
                Health.Builder sonde = Health.unknown()
                        .withDetail("mesure", "indisponible (table absente ou erreur SQL)");
                if (!mesures.anomalies().isEmpty()) {
                    sonde.withDetail("anomalies", mesures.anomalies());
                }
                return sonde.build();
            }
            long enAttente = mesures.syncOutboxEnAttente();
            double retardSecondes = mesures.syncOutboxRetardSecondes();
            boolean sain = enAttente < SEUIL_EN_ATTENTE && retardSecondes < SEUIL_RETARD_SECONDES;
            return (sain ? Health.up() : Health.down())
                    .withDetail("enAttente", enAttente)
                    .withDetail("retardSecondes", retardSecondes)
                    .withDetail("seuilEnAttente", SEUIL_EN_ATTENTE)
                    .withDetail("seuilRetardSecondes", SEUIL_RETARD_SECONDES)
                    .withDetail("slo", SLO)
                    .build();
        } catch (RuntimeException e) {
            // Ceinture et bretelles : une sonde ne casse jamais l'application.
            return Health.unknown()
                    .withException(e)
                    .withDetail("mesure", "erreur inattendue — la sonde reste silencieuse")
                    .build();
        }
    }

    // ------------------------------------------------------------------
    // Point de lecture programmatique (outils d'exploitation, tests)
    // ------------------------------------------------------------------

    /** Représentation simple de l'état courant (jamais d'exception). */
    public Map<String, Object> etat() {
        SnapshotMesures mesures = source.mesurer();
        return Map.of(
                "enAttente", String.valueOf(mesures.syncOutboxEnAttente()),
                "retardSecondes", String.valueOf(mesures.syncOutboxRetardSecondes()),
                "seuilEnAttente", SEUIL_EN_ATTENTE,
                "seuilRetardSecondes", SEUIL_RETARD_SECONDES);
    }
}
