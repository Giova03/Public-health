package bf.publichealth.modules.sync.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import bf.publichealth.modules.sync.domain.CurseurDelta;

/**
 * Port de lecture du miroir patient (descendant, épique E2).
 *
 * <p>NOTE D'ARCHITECTURE (pour l'intégrateur) : la loi des modules veut
 * que sync passe par une interface Java du module propriétaire. Faute
 * d'un port « miroir » exposé par identity aujourd'hui, l'implémentation
 * {@code MiroirPatientsPg} lit directement le schéma {@code identity}
 * (SELECT sans JOIN inter-schemas). Recommandation : déplacer ce port
 * dans le module identity lors de l'épique suivante — l'interface est
 * déjà ici, prête à être adoptée.</p>
 */
public interface MiroirPatients {

    /**
     * UUID serveur d'un patient créé offline, à partir de sa clé cliente
     * (client_request_id). Sert à résoudre les références patientId des
     * opérations cliniques envoyées dans le même lot.
     */
    Optional<UUID> idPatientParClientRequestId(UUID clientRequestId);

    /** Page de patients dont la clé (updated_at, id) dépasse le curseur. */
    PageDelta patientsApres(CurseurDelta curseur, int limite);

    /** Une ligne du miroir — le strict nécessaire pour IndexedDB. */
    record PatientMiroir(UUID id, String phReference, boolean active, UUID masterId,
                         String gender, LocalDate birthDate, boolean birthDateApproximative,
                         long version, Instant updatedAt,
                         List<Nom> names, List<Telecom> telecoms) {
    }

    record Nom(String use, String family, String given) {
    }

    record Telecom(String system, String value, String use) {
    }

    record PageDelta(CurseurDelta curseur, boolean hasMore, List<PatientMiroir> patients) {
    }
}
