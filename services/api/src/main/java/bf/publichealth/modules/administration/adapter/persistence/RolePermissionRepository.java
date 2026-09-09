package bf.publichealth.modules.administration.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lecture de la matrice rôle → permissions (valeur de référence V12).
 */
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, UUID> {

    List<RolePermissionEntity> findByRoleOrderByIdAsc(String role);
}
