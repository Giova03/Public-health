package bf.publichealth.modules.identity.domain;

import java.util.UUID;

/**
 * Dossier fusionné — inaccessible en tant que tel (410 Gone).
 * Le consommateur est redirigé vers le dossier maître.
 */
public class PatientMergedException extends RuntimeException {

    private final UUID masterId;

    public PatientMergedException(UUID mergedId, UUID masterId) {
        super("Le dossier %s a été fusionné dans %s (fusion irréversible, tracée dans identity.merge_log)"
                .formatted(mergedId, masterId));
        this.masterId = masterId;
    }

    public UUID getMasterId() {
        return masterId;
    }
}
