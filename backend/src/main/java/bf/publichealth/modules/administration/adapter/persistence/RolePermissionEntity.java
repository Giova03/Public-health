package bf.publichealth.modules.administration.adapter.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ligne de la matrice rôle → permission (migration V12) — valeur de
 * référence des permissions effectives. Semée par V12 à l'identique
 * du domaine pur RolesPermissions ; l'égalité est verrouillée par
 * BackofficeIT.
 */
@Entity
@Table(name = "role_permission", schema = "administration")
public class RolePermissionEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 24)
    private String role;

    @Column(nullable = false)
    private String permission;

    protected RolePermissionEntity() {
    }

    public RolePermissionEntity(UUID id, String role, String permission) {
        this.id = id;
        this.role = role;
        this.permission = permission;
    }

    public UUID getId() {
        return id;
    }

    public String getRole() {
        return role;
    }

    public String getPermission() {
        return permission;
    }
}
