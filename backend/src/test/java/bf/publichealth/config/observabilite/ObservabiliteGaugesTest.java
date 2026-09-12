package bf.publichealth.config.observabilite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires des gauges — publication Micrometer des métriques
 * métier E8 : noms exacts ({@code ph_sync_ops{result=…}},
 * {@code ph_paiements{statut=…}} — sans suffixe « _total », réservé aux
 * compteurs par le client Prometheus), valeurs numériques au scrape,
 * NaN si la mesure est indisponible, rafraîchissement manuel après « seed ».
 *
 * <p>Le premier cycle applicatif part de {@code afterSingletonsInstantiated}
 * (après Flyway) : ici il est simulé par un appel explicite à
 * {@code rafraichir()} — jamais mesuré au seul constructeur.</p>
 */
class ObservabiliteGaugesTest {

    private static SnapshotMesures snapshot(Long attente) {
        Map<String, Long> resultats = new LinkedHashMap<>();
        for (String resultat : SnapshotMesures.RESULTATS_SYNC_OP) {
            resultats.put(resultat, 0L);
        }
        resultats.put("APPLIED", 10L);
        resultats.put("REJECTED", 2L);
        resultats.put("CONFLICT", 1L);
        Map<String, Long> statuts = new LinkedHashMap<>();
        for (String statut : SnapshotMesures.STATUTS_PAIEMENT) {
            statuts.put(statut, 0L);
        }
        statuts.put("INITIATED", 4L);
        statuts.put("PENDING", 1L);
        return new SnapshotMesures(attente, 123.5, resultats,
                statuts, 9L, 5_400.0, List.of());
    }

    /** Source factice « base coupée » : snapshot vide, sans exception (contrat E8). */
    private static final SourceMesures SOURCE_VIDE = () -> SnapshotMesures.VIDE;

    @Test
    @DisplayName("rafraîchissement : les 15 séries ph_* publiées avec valeurs numériques")
    void publicationDesGauges() {
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        ObservabiliteGauges gauges = new ObservabiliteGauges(
                () -> snapshot(7L), registre);
        // Premier cycle applicatif (après Flyway en production).
        gauges.rafraichir();

        assertThat(registre.get(ObservabiliteGauges.M_OUTBOX_EN_ATTENTE).gauge().value())
                .isEqualTo(7.0);
        assertThat(registre.get(ObservabiliteGauges.M_OUTBOX_RETARD).gauge().value())
                .isEqualTo(123.5);
        // Une seule métrique ph_sync_ops, taggée result (APPLIED/REJECTED/CONFLICT).
        assertThat(registre.get(ObservabiliteGauges.M_OPS)
                .tag(ObservabiliteGauges.TAG_RESULTAT, "APPLIED").gauge().value())
                .isEqualTo(10.0);
        assertThat(registre.get(ObservabiliteGauges.M_OPS)
                .tag(ObservabiliteGauges.TAG_RESULTAT, "REJECTED").gauge().value())
                .isEqualTo(2.0);
        assertThat(registre.get(ObservabiliteGauges.M_OPS)
                .tag(ObservabiliteGauges.TAG_RESULTAT, "CONFLICT").gauge().value())
                .isEqualTo(1.0);
        assertThat(registre.get(ObservabiliteGauges.M_IDENTITY_FILE_REVUE).gauge().value())
                .isEqualTo(9.0);
        assertThat(registre.get(ObservabiliteGauges.M_RECONCILIATION_AGE).gauge().value())
                .isEqualTo(5_400.0);
        // Une seule métrique ph_paiements, taggée statut : 8 séries
        // (les 2 semées + les 6 à zéro).
        assertThat(registre.get(ObservabiliteGauges.M_PAIEMENTS)
                .tag(ObservabiliteGauges.TAG_STATUT, "INITIATED").gauge().value())
                .isEqualTo(4.0);
        assertThat(registre.get(ObservabiliteGauges.M_PAIEMENTS)
                .tag(ObservabiliteGauges.TAG_STATUT, "PENDING").gauge().value())
                .isEqualTo(1.0);
        assertThat(registre.get(ObservabiliteGauges.M_PAIEMENTS)
                .tag(ObservabiliteGauges.TAG_STATUT, "RECONCILED").gauge().value())
                .isEqualTo(0.0);
        assertThat(registre.getMeters().stream()
                .filter(m -> m.getId().getName().equals(ObservabiliteGauges.M_PAIEMENTS))
                .count()).isEqualTo(8);
        assertThat(registre.getMeters().stream()
                .filter(m -> m.getId().getName().equals(ObservabiliteGauges.M_OPS))
                .count()).isEqualTo(3);
    }

    @Test
    @DisplayName("mesure indisponible : gauge NaN (jamais de valeur trompeuse, jamais d'exception)")
    void mesureIndisponibleNan() {
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        ObservabiliteGauges gauges = new ObservabiliteGauges(SOURCE_VIDE, registre);
        gauges.rafraichir();
        // SOURCE_VIDE : mesurer() ne lève pas, retourne le snapshot VIDE.
        assertThat(registre.get(ObservabiliteGauges.M_OUTBOX_EN_ATTENTE).gauge().value())
                .isNaN();
        assertThat(registre.get(ObservabiliteGauges.M_OPS)
                .tag(ObservabiliteGauges.TAG_RESULTAT, "APPLIED").gauge().value()).isNaN();
        assertThat(registre.get(ObservabiliteGauges.M_PAIEMENTS)
                .tag(ObservabiliteGauges.TAG_STATUT, "INITIATED").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("seed puis refresh manuel : la gauge passe au-dessus de zéro")
    void seedPuisRafraichissementManuel() {
        AtomicReference<SnapshotMesures> etat = new AtomicReference<>(snapshot(0L));
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        ObservabiliteGauges gauges = new ObservabiliteGauges(etat::get, registre);
        gauges.rafraichir();
        assertThat(registre.get(ObservabiliteGauges.M_OUTBOX_EN_ATTENTE).gauge().value())
                .isEqualTo(0.0);

        // « INSERT outbox » simulé : la source relit 3 événements.
        etat.set(snapshot(3L));
        gauges.rafraichir();

        assertThat(registre.get(ObservabiliteGauges.M_OUTBOX_EN_ATTENTE).gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("état inédit de paiement (migration future) : série publiée au vol, cardinalité bornée")
    void etatIneditPublieAuVol() {
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        Map<String, Long> statuts = new LinkedHashMap<>();
        statuts.put("EXPIRED", 5L);
        SnapshotMesures mesureFuture = new SnapshotMesures(
                0L, 0.0, Map.of("APPLIED", 0L, "REJECTED", 0L, "CONFLICT", 0L),
                statuts, 0L, 60.0, List.of());
        ObservabiliteGauges gauges = new ObservabiliteGauges(() -> mesureFuture, registre);
        gauges.rafraichir();

        // Les 8 séries du CHECK V2 + la série inédite portée par la mesure.
        assertThat(registre.get(ObservabiliteGauges.M_PAIEMENTS)
                .tag(ObservabiliteGauges.TAG_STATUT, "EXPIRED").gauge().value())
                .isEqualTo(5.0);
        assertThat(registre.getMeters().stream()
                .filter(m -> m.getId().getName().equals(ObservabiliteGauges.M_PAIEMENTS))
                .count()).isEqualTo(9);
    }
}
