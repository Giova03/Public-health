package bf.publichealth.modules.identity.domain;

import java.util.List;
import java.util.UUID;

/**
 * Doublon détecté à la création — le 409 EST le contrat UX (ADR-003) :
 * la réponse embarque les candidats existants pour que l'agent décide
 * humainement, jamais l'API ne fusionne ou ne crée en silence.
 */
public class PatientDuplicateException extends RuntimeException {

    /** Candidat existant, tel que l'agent doit le voir à l'écran. */
    public record Candidate(UUID id, String phReference, String family, String given,
                            String birthDate, String gender, double score, String method,
                            boolean blocking) {
    }

    private final List<Candidate> candidates;

    public PatientDuplicateException(List<Candidate> candidates) {
        super("Patient probablement déjà enregistré — %d candidat(s) détecté(s)"
                .formatted(candidates.size()));
        this.candidates = List.copyOf(candidates);
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }
}
