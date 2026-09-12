package bf.publichealth.modules.payments.domain;

import java.util.UUID;

/** Facture introuvable — 404 propre, aucune fuite d'existence. */
public class InvoiceIntrouvableException extends RuntimeException {

    private final UUID invoiceId;

    public InvoiceIntrouvableException(UUID invoiceId) {
        super("Facture introuvable : " + invoiceId);
        this.invoiceId = invoiceId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }
}
