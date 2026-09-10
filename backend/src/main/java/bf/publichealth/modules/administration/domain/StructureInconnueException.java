package bf.publichealth.modules.administration.domain;

import java.util.UUID;

/**
 * Structure de rattachement inconnue — 404 au niveau HTTP.
 *
 * <p>structure_id est une référence LOGIQUE sans FK (loi n°3) :
 * l'existence est vérifiée par le port StructureLookup vers le module
 * organization au moment de l'invitation. Une invitation vers une
 * structure inconnue est refusée — jamais de référence pendouillante
 * créée en silence.</p>
 */
public class StructureInconnueException extends RuntimeException {

    private final UUID structureId;

    public StructureInconnueException(UUID structureId) {
        super("Structure de rattachement inconnue : " + structureId
                + " (vérifiez l'annuaire /api/v1/organizations)");
        this.structureId = structureId;
    }

    public UUID getStructureId() {
        return structureId;
    }
}
