package bf.publichealth.modules.payments.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentTransitionRepository extends JpaRepository<PaymentTransitionEntity, Long> {

    long countByPaymentId(UUID paymentId);
}
