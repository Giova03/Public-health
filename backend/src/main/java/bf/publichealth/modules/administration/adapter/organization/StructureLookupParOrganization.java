package bf.publichealth.modules.administration.adapter.organization;

import java.util.UUID;

import org.springframework.stereotype.Component;

import bf.publichealth.modules.administration.application.StructureLookup;
import bf.publichealth.modules.organization.adapter.persistence.StructureRepository;

/**
 * Adaptateur du port {@link StructureLookup} sur l'annuaire
 * organization — unique point de couplage entre les deux modules
 * (patron PatientLookupParIdentity du module prescription, aucun
 * JOIN inter-schémas).
 */
@Component
public class StructureLookupParOrganization implements StructureLookup {

    private final StructureRepository structureRepository;

    public StructureLookupParOrganization(StructureRepository structureRepository) {
        this.structureRepository = structureRepository;
    }

    @Override
    public boolean structureConnue(UUID structureId) {
        return structureId != null && structureRepository.existsById(structureId);
    }
}
