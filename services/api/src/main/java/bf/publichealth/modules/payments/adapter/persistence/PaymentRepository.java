package bf.publichealth.modules.payments.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    /** Idempotence de l'initiation : une clé, un paiement, jamais deux. */
    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByProviderRef(String providerRef);
}
