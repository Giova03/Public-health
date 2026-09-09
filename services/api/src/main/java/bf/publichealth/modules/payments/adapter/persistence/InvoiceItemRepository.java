package bf.publichealth.modules.payments.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItemEntity, UUID> {

    List<InvoiceItemEntity> findByInvoiceIdOrderByIdAsc(UUID invoiceId);
}
