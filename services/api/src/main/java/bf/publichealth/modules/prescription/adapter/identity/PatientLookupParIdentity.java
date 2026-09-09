package bf.publichealth.modules.prescription.adapter.identity;

import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.publichealth.modules.identity.application.PatientService;
import bf.publichealth.modules.identity.domain.PatientMergedException;
import bf.publichealth.modules.prescription.application.PatientLookup;

/**
 * Adaptateur du port {@link PatientLookup} sur le module identity.
 *
 * <p>Le module identity n'expose pas (encore) d'interface dédiée à la
 * vérification d'existence : payments et clinical ne référencent le
 * patient que par uuid brut, sans vérification. Ce module en a besoin —
 * on interroge donc le service applicatif identity
 * ({@link PatientService#find}), qui est sa façade de cas d'usage, et on
 * traduit son contrat d'exceptions (absent → IllegalArgumentException,
 * fusionné → PatientMergedException) en simple booléen. C'est l'unique
 * classe du module prescription qui importe identity — à remplacer par
 * une vraie interface exposée par identity le jour où un second
 * consommateur apparaît.</p>
 */
@Component
public class PatientLookupParIdentity implements PatientLookup {

    private final PatientService patientService;

    public PatientLookupParIdentity(PatientService patientService) {
        this.patientService = patientService;
    }

    @Override
    public boolean patientActif(UUID patientId) {
        try {
            var patient = patientService.find(patientId).patient();
            // Fusionné (master_id posé, actif=false) : le dossier n'existe plus seul.
            return Boolean.TRUE.equals(patient.getActive()) && patient.getMasterId() == null;
        } catch (PatientMergedException e) {
            return false;
        } catch (IllegalArgumentException e) {
            // Contrat de PatientService.find : patient introuvable.
            return false;
        }
    }
}
