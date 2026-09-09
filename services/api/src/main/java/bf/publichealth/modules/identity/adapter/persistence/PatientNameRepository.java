package bf.publichealth.modules.identity.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientNameRepository extends JpaRepository<PatientNameEntity, UUID> {

    List<PatientNameEntity> findByPatientId(UUID patientId);

    /**
     * Candidats par préfixe de patronyme — compatible avec l'index
     * idx_patient_name_search (lower(family), lower(given)) : le préfixe
     * est déjà normalisé (minuscules, sans accents) côté application.
     * concat(:prefix, '%') : l'ancrage est un VRAI préfixe, pas une égalité.
     */
    @Query("select n from PatientNameEntity n where lower(n.family) like concat(:prefix, '%')")
    List<PatientNameEntity> findByFamilyPrefix(@Param("prefix") String prefix);
}
