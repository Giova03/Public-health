package bf.publichealth.config.observabilite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Tests unitaires de la sonde phOutbox — UP sous les seuils (volume
 * &lt; 1000 ET retard &lt; 3600 s), DOWN au-delà (détail : valeurs + seuils),
 * UNKNOWN quand la mesure est indisponible (table absente, erreur SQL,
 * exception inattendue du JdbcTemplate/DataSource — jamais DOWN par erreur
 * de mesure : la santé « base » est portée par phBase).
 */
class SondePhOutboxTest {

    private static SnapshotMesures mesures(Long enAttente, Double retardSecondes) {
        return new SnapshotMesures(enAttente, retardSecondes,
                Map.of("APPLIED", 0L, "REJECTED", 0L, "CONFLICT", 0L),
                Map.of(), 0L, 60.0, List.of());
    }

    @Test
    @DisplayName("outbox saine : UP avec valeurs et seuils en détail")
    void outboxSaineUp() {
        SondePhOutbox sonde = new SondePhOutbox(
                () -> mesures(12L, 240.0));

        Health sante = sonde.health();

        assertThat(sante.getStatus()).isEqualTo(Status.UP);
        assertThat(sante.getDetails()).containsEntry("enAttente", 12L)
                .containsEntry("retardSecondes", 240.0)
                .containsEntry("seuilEnAttente", SondePhOutbox.SEUIL_EN_ATTENTE)
                .containsEntry("seuilRetardSecondes", SondePhOutbox.SEUIL_RETARD_SECONDES)
                .containsKey("slo");
    }

    @Test
    @DisplayName("retard > 3600 s : DOWN (l'alerte « health DOWN » prend le relais)")
    void retardExcessifDown() {
        SondePhOutbox sonde = new SondePhOutbox(
                () -> mesures(3L, 4_500.0));

        Health sante = sonde.health();

        assertThat(sante.getStatus()).isEqualTo(Status.DOWN);
        assertThat(sante.getDetails()).containsEntry("retardSecondes", 4_500.0);
    }

    @Test
    @DisplayName("accumulation ≥ 1000 événements : DOWN même sans retard horloge")
    void accumulationExcessiveDown() {
        SondePhOutbox sonde = new SondePhOutbox(
                () -> mesures(1_000L, 30.0));

        Health sante = sonde.health();

        assertThat(sante.getStatus()).isEqualTo(Status.DOWN);
        assertThat(sante.getDetails()).containsEntry("enAttente", 1_000L);
    }

    @Test
    @DisplayName("table absente / mesure indisponible : UNKNOWN, JAMAIS DOWN")
    void mesureIndisponibleUnknown() {
        // Table absente (42P01) : le champ vaut null, l'anomalie l'explique.
        SondePhOutbox sonde = new SondePhOutbox(
                () -> new SnapshotMesures(null, null, null, null, null, null,
                        List.of("table sync.outbox absente (SQLState 42P01) — "
                                + "migration non appliquée ?")));

        Health sante = sonde.health();

        assertThat(sante.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(String.valueOf(sante.getDetails().get("mesure")))
                .contains("indisponible");
        assertThat(sante.getDetails()).containsKey("anomalies");
    }

    @Test
    @DisplayName("JdbcTemplate qui lève (RuntimeException imprévue) : UNKNOWN, jamais DOWN, jamais de propagation")
    void exceptionInattendueUnknown() {
        SondePhOutbox sonde = new SondePhOutbox(() -> {
            throw new IllegalStateException("pool épuisé (simulateur)");
        });

        Health sante = sonde.health();

        // Ceinture et bretelles : la sonde avale l'exception imprévue.
        assertThat(sante.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(String.valueOf(sante.getDetails().get("mesure")))
                .contains("erreur inattendue");
    }

    @Test
    @DisplayName("état programmatique : seuils exposés sans exception")
    void etatProgrammatique() {
        SondePhOutbox sonde = new SondePhOutbox(() -> mesures(0L, 0.0));
        Map<String, Object> etat = sonde.etat();
        assertThat(etat).containsKeys("enAttente", "retardSecondes",
                "seuilEnAttente", "seuilRetardSecondes");
    }
}
