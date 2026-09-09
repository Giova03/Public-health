package bf.publichealth.modules.sync.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Port d'écriture clinique pour l'uplink offline (épique E2).
 *
 * <p>NOTE D'ARCHITECTURE (pour l'intégrateur) : le module clinical est
 * encore vide (E3 livrera ses cas d'usage). Par périmètre imposé, ces
 * insertions append-only vivent dans l'adaptateur du module sync ;
 * elles devront déménager dans le module clinical quand il existera.
 * Les gardes append-only SQL (V3) s'appliquent déjà, quelle que soit
 * la porte d'entrée.</p>
 *
 * <p>Création offline : l'UUID est fourni PAR LE CLIENT (v7),
 * {@code created_via='offline'}, {@code synced_at} laissé NULL (le
 * miroir descendant clinical le remplira plus tard).</p>
 */
public interface EcrivainClinique {

    /** L'entité clinique existe-t-elle déjà ? (append-only : une seule vie par UUID) */
    boolean existe(String entite, UUID id);

    void insererEncounter(UUID id, Encounter charge);

    void insererObservation(UUID id, Observation charge);

    void insererCondition(UUID id, Condition charge);

    /** Charge offline d'une rencontre — l'UUID vient de l'op (côté client). */
    record Encounter(UUID patientId, UUID facilityId, UUID practitionerId,
                     String encounterClass, String reason,
                     Instant startedAt, Instant endedAt) {
    }

    /** Charge offline d'une observation (append-only, jamais réécrite). */
    record Observation(UUID encounterId, UUID patientId, String code,
                       String valueText, BigDecimal valueNum, String status,
                       Instant effectiveAt) {
    }

    /** Charge offline d'un problème de santé (condition). */
    record Condition(UUID encounterId, UUID patientId, String code,
                     String clinicalStatus, Instant recordedAt) {
    }
}
