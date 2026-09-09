package bf.publichealth.modules.prescription.domain;

import java.util.UUID;

/**
 * Aucun patient actif ne correspond à l'identifiant fourni : la
 * prescription n'est PAS créée (loi n°3 du monolithe modulaire —
 * patient_id sans FK, cohérence applicative via le module identity).
 * La couche API répond 404 (problem+json, RFC 7807).
 */
public class PatientIntrouvableException extends RuntimeException {

    private final UUID patientId;

    public PatientIntrouvableException(UUID patientId) {
        super("Aucun patient actif ne correspond à l'identifiant %s — "
                + "la prescription n'est pas créée".formatted(patientId));
        this.patientId = patientId;
    }

    public UUID getPatientId() {
        return patientId;
    }
}
