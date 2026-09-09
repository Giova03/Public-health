package bf.publichealth.config.observabilite;

/**
 * Point de lecture des mesures d'exploitation — épique E8.
 *
 * <p>Contrat de robustesse : {@link #mesurer()} NE LÈVE JAMAIS. Une mesure
 * impossible (table absente, erreur SQL, connexion refusée) se traduit par
 * un champ {@code null} du {@link SnapshotMesures} et une entrée dans
 * {@code anomalies} — l'application ne doit jamais tomber parce qu'on la
 * mesure. Les gauges publient {@code NaN}, les sondes dégradent en
 * {@code UNKNOWN}.</p>
 */
public interface SourceMesures {

    /** Photographie des compteurs/âges d'exploitation (jamais d'exception). */
    SnapshotMesures mesurer();
}
