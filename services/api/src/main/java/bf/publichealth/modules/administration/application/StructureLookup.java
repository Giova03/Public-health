package bf.publichealth.modules.administration.application;

import java.util.UUID;

/**
 * Port vers l'annuaire des structures sanitaires (module organization).
 *
 * <p>Loi n°1 des modules : administration ne JOIN jamais organization —
 * l'existence d'une structure de rattachement est vérifiée par ce port
 * au moment de l'invitation. Adapté par
 * {@code adapter/organization/StructureLookupParOrganization} (patron
 * PatientLookup du module prescription : unique point de couplage,
 * remplacé par une vraie interface organization si un second
 * consommateur apparaît).</p>
 */
public interface StructureLookup {

    /** La structure existe-t-elle dans l'annuaire (active ou non) ? */
    boolean structureConnue(UUID structureId);
}
