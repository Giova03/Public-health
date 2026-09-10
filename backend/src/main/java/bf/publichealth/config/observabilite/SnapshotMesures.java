package bf.publichealth.config.observabilite;

import java.util.List;
import java.util.Map;

/**
 * Photographie instantanée des mesures d'exploitation — épique E8.
 *
 * <p>Un champ {@code null} signifie « mesure INDISPONIBLE » (table absente,
 * erreur SQL, connexion impossible) : la gauge correspondante vaut alors
 * {@code NaN} et la sonde dégrade en {@code UNKNOWN} — JAMAIS en DOWN par
 * erreur de mesure (la santé « base » est portée par {@code phBase},
 * la santé « seuils » par {@code phOutbox}).</p>
 *
 * <p>Contenu strictement non sensible : compteurs et âges uniquement —
 * aucun identifiant patient, aucun montant, aucune donnée métier nominative.</p>
 *
 * @param syncOutboxEnAttente count de {@code sync.outbox} non publiés
 * @param syncOutboxRetardSecondes âge en secondes du plus ancien non publié
 * @param syncOpsParResultat cumul des {@code sync.op} par résultat
 *        ({@code APPLIED}/{@code REJECTED}/{@code CONFLICT} — CHECK V4)
 * @param paiementsParStatut compteurs par état des 8 états V2 de
 *        {@code payments.payment}
 * @param identityFileRevue file de revue {@code identity.identity_match}
 *        en {@code PENDING}
 * @param reconciliationDerniereAgeSecondes âge en secondes du plus récent
 *        {@code payments.reconciliation_run} terminé ({@code finished_at})
 * @param anomalies descriptions courtes des mesures indisponibles (jamais
 *        de secret : messages SQL sans données)
 */
public record SnapshotMesures(
        Long syncOutboxEnAttente,
        Double syncOutboxRetardSecondes,
        Map<String, Long> syncOpsParResultat,
        Map<String, Long> paiementsParStatut,
        Long identityFileRevue,
        Double reconciliationDerniereAgeSecondes,
        List<String> anomalies) {

    /** Les 3 résultats d'uplink de sync.op (CHECK V4) — séries fixes. */
    public static final List<String> RESULTATS_SYNC_OP = List.of(
            "APPLIED", "REJECTED", "CONFLICT");

    /** Les 8 états forward-only de payments.payment (CHECK V2) — séries fixes. */
    public static final List<String> STATUTS_PAIEMENT = List.of(
            "INITIATED", "PENDING", "AUTHORIZED", "SUCCEEDED",
            "FAILED", "CANCELLED", "REFUNDED", "RECONCILED");

    /** Snapshot vide : aucune mesure disponible (gauge NaN, sonde UNKNOWN). */
    public static final SnapshotMesures VIDE =
            new SnapshotMesures(null, null, null, null, null, null, null);

    public SnapshotMesures {
        syncOpsParResultat = syncOpsParResultat == null
                ? Map.of() : Map.copyOf(syncOpsParResultat);
        paiementsParStatut = paiementsParStatut == null
                ? Map.of() : Map.copyOf(paiementsParStatut);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }
}
