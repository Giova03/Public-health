package bf.publichealth.modules.organization.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Accès à l'annuaire des structures sanitaires.
 *
 * <p>Les filtres combinés (région, type, texte, active) passent par les
 * {@link JpaSpecificationExecutor Specifications} — chaque filtre est
 * optionnel, la combinaison reste une seule requête.</p>
 */
public interface StructureRepository
        extends JpaRepository<StructureEntity, UUID>, JpaSpecificationExecutor<StructureEntity> {

    /** Unicité exacte (garde SQL V12). */
    Optional<StructureEntity> findByCode(String code);

    /** Garde applicative PLUS STRICTE que l'index SQL : insensible à la casse. */
    Optional<StructureEntity> findByCodeIgnoreCase(String code);
}
