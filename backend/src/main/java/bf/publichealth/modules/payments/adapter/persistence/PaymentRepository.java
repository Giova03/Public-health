package bf.publichealth.modules.payments.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import bf.publichealth.modules.payments.domain.PaymentState;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    /** Idempotence de l'initiation : une clé, un paiement, jamais deux. */
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByProviderRef(String providerRef);

    /**
     * Paiements à examiner par la réconciliation : états non scellés
     * (INITIATED/PENDING/AUTHORIZED/SUCCEEDED) et interrogeables
     * (provider_ref connu — sans référence, le prestataire ne dit rien).
     */
    List<PaymentEntity> findByStateInAndProviderRefIsNotNull(Collection<PaymentState> states);

    /** Paiements liés à une facture (le lien est établi à l'initiation, V2). */
    List<PaymentEntity> findByInvoiceId(UUID invoiceId);
}
