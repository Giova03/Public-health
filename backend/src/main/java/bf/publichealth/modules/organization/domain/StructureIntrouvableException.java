package bf.publichealth.modules.organization.domain;

import java.util.UUID;

/**
 * Structure sanitaire introuvable — 404 au niveau HTTP (RFC 7807).
 */
public class StructureIntrouvableException extends RuntimeException {

    private final UUID structureId;

    public StructureIntrouvableException(UUID structureId) {
        super("Structure sanitaire introuvable : " + structureId);
        this.structureId = structureId;
    }

    public UUID getStructureId() {
        return structureId;
    }
}
