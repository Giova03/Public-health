package bf.publichealth.modules.audit.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Dépôt du registre break-the-glass — files de lecture :
 * par patient (plus récents d'abord) et en attente d'examen.
 */
public interface AccesUrgenceRepository extends JpaRepository<AccesUrgenceEntity, UUID> {

    /** Historique des brèches d'un patient, plus récentes d'abord. */
    List<AccesUrgenceEntity> findByPatientIdOrderByOpenedAtDesc(UUID patientId);

    /** File des brèches NON revues (examen a posteriori), plus récentes d'abord. */
    List<AccesUrgenceEntity> findByReviewedFalseOrderByOpenedAtDesc();
}
