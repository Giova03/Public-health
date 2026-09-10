package bf.publichealth.modules.fhir.domain;

import java.util.UUID;

/**
 * Dossier patient fusionné — 410 Gone, OperationOutcome issue
 * {@code deleted} (absorbé par fusion).
 *
 * <p>Miroir du contrat identity (E1) : un dossier fusionné n'existe plus en
 * tant que tel, il pointe irréversiblement vers son maître. L'OperationOutcome
 * porte une extension {@code master-id} (valueReference Patient/&lt;uuid&gt;)
 * pour que le consommateur suive le dossier maître sans deviner.</p>
 */
public class DossierFusionneException extends RuntimeException {

    private final UUID dossierFusionne;
    private final UUID masterId;

    public DossierFusionneException(UUID dossierFusionne, UUID masterId) {
        super("Le dossier %s a été fusionné dans %s (fusion irréversible, tracée dans identity.merge_log)"
                .formatted(dossierFusionne, masterId));
        this.dossierFusionne = dossierFusionne;
        this.masterId = masterId;
    }

    public UUID getDossierFusionne() {
        return dossierFusionne;
    }

    public UUID getMasterId() {
        return masterId;
    }
}
