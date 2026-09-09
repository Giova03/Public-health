package bf.publichealth.modules.prescription.application;

import java.util.UUID;

/**
 * Port hexagonal de sortie : vérification d'existence d'un patient.
 *
 * <p>Le module prescription référence un patient par colonne uuid SANS
 * FK inter-schémas (loi n°3 du monolithe modulaire) : la cohérence est
 * assurée par l'application, au moment de la création, via ce port.
 * L'implémentation vit dans l'adaptateur {@code adapter.identity} et
 * s'appuie sur le service applicatif du module identity — seul point de
 * couplage entre les deux modules (ADR-001 : interfaces, jamais de JOIN).</p>
 */
public interface PatientLookup {

    /**
     * Le dossier patient existe-t-il et est-il ACTIF (non fusionné) ?
     * Un dossier fusionné dans un maître n'est plus une cible valide.
     */
    boolean patientActif(UUID patientId);
}
