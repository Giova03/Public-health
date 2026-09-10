package bf.publichealth.modules.audit.adapter.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEntryRepository extends JpaRepository<AuditEntryEntity, Long> {

    /** Dernière entrée du journal — sert de maillon au chaînage par hachage. */
    Optional<AuditEntryEntity> findTopByOrderByIdDesc();
}
