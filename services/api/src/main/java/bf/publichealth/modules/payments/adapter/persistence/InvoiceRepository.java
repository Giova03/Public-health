package bf.publichealth.modules.payments.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

    /** Idempotence de création : une clé client = une facture, jamais deux. */
    Optional<InvoiceEntity> findByClientRequestId(UUID clientRequestId);

    /**
     * Factures d'un patient, la plus récente d'abord. L'horodatage de
     * référence est l'émission (un brouillon non émis retombe sur sa
     * création) — l'index V9 (patient_id, issued_at DESC) sert l'égalité
     * sur patient_id.
     */
    @Query("""
            select i from InvoiceEntity i
            where i.patientId = :patientId
            order by coalesce(i.issuedAt, i.createdAt) desc, i.id asc
            """)
    List<InvoiceEntity> listerParPatient(UUID patientId);
}
