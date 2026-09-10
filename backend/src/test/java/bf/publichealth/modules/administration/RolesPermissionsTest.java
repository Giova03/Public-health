package bf.publichealth.modules.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.RolesPermissions;

/**
 * Tests unitaires de la cartographie rôle → permissions (domaine pur
 * de l'épique E6) — la matrice est EXHAUSTIVE et CLOSE : chaque rôle
 * porte au moins ses permissions de base, aucune permission
 * n'est orpheline, personne ne mute la matrice.
 */
class RolesPermissionsTest {

    // ------------------------------------------------------------------
    // Permissions de base par rôle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("admin : le back-office ET la réconciliation ET la supervision globale")
    void adminPorteLeBackOffice() {
        Set<String> permissions = RolesPermissions.permissionsDe(RoleUtilisateur.ADMIN);
        assertThat(permissions).contains(
                RolesPermissions.ADMIN_GERER,
                RolesPermissions.PAIEMENT_RECONCILIER,
                RolesPermissions.AUDIT_LIRE,
                RolesPermissions.PATIENT_ECRIRE,
                RolesPermissions.PRESCRIPTION_ECRIRE,
                RolesPermissions.DISPENSER);
        // L'admin porte TOUTES les permissions de la nomenclature.
        assertThat(permissions).isEqualTo(RolesPermissions.TOUTES_LES_PERMISSIONS);
    }

    @Test
    @DisplayName("medecin : le dossier patient et la prescription")
    void medecinPrescrit() {
        assertThat(RolesPermissions.permissionsDe(RoleUtilisateur.MEDECIN)).containsExactlyInAnyOrder(
                RolesPermissions.PATIENT_LIRE,
                RolesPermissions.PATIENT_ECRIRE,
                RolesPermissions.PRESCRIPTION_LIRE,
                RolesPermissions.PRESCRIPTION_ECRIRE);
    }

    @Test
    @DisplayName("infirmier : admission MPI et frais d'accès (CSPS), pas de prescription")
    void infirmierAdmetSansPrescrire() {
        Set<String> permissions = RolesPermissions.permissionsDe(RoleUtilisateur.INFIRMIER);
        assertThat(permissions).contains(
                RolesPermissions.PATIENT_LIRE,
                RolesPermissions.PATIENT_ECRIRE,
                RolesPermissions.PAIEMENT_INITIER);
        assertThat(permissions).doesNotContain(
                RolesPermissions.PRESCRIPTION_ECRIRE,
                RolesPermissions.DISPENSER);
    }

    @Test
    @DisplayName("pharmacien : la dispensation, jamais la prescription")
    void pharmacienDispenseSansPrescrire() {
        Set<String> permissions = RolesPermissions.permissionsDe(RoleUtilisateur.PHARMACIEN);
        assertThat(permissions).contains(
                RolesPermissions.DISPENSER,
                RolesPermissions.PRESCRIPTION_LIRE,
                RolesPermissions.PATIENT_LIRE);
        assertThat(permissions).doesNotContain(
                RolesPermissions.PRESCRIPTION_ECRIRE,
                RolesPermissions.PATIENT_ECRIRE);
    }

    @Test
    @DisplayName("agent_financier : initiation et suivi des paiements, pas de soin")
    void agentFinancierPaie() {
        Set<String> permissions = RolesPermissions.permissionsDe(RoleUtilisateur.AGENT_FINANCIER);
        assertThat(permissions).containsExactlyInAnyOrder(
                RolesPermissions.PATIENT_LIRE,
                RolesPermissions.PAIEMENT_INITIER,
                RolesPermissions.PAIEMENT_LIRE);
        assertThat(permissions).doesNotContain(RolesPermissions.PAIEMENT_RECONCILIER);
    }

    @Test
    @DisplayName("superviseur : lecture seule (paiements, audit) — AUCUNE écriture")
    void superviseurEstLectureSeule() {
        Set<String> permissions = RolesPermissions.permissionsDe(RoleUtilisateur.SUPERVISEUR);
        assertThat(permissions).contains(
                RolesPermissions.AUDIT_LIRE,
                RolesPermissions.PAIEMENT_LIRE,
                RolesPermissions.PRESCRIPTION_LIRE);
        assertThat(permissions).doesNotContain(
                RolesPermissions.ADMIN_GERER,
                RolesPermissions.PATIENT_ECRIRE,
                RolesPermissions.PRESCRIPTION_ECRIRE,
                RolesPermissions.DISPENSER,
                RolesPermissions.PAIEMENT_INITIER,
                RolesPermissions.PAIEMENT_RECONCILIER);
    }

    // ------------------------------------------------------------------
    // Invariants de la nomenclature
    // ------------------------------------------------------------------

    @Test
    @DisplayName("chaque rôle porte la permission de base : patient:lire (identifier avant d'agir)")
    void chaqueRoleIdentifieLePatient() {
        for (RoleUtilisateur role : RoleUtilisateur.values()) {
            assertThat(RolesPermissions.permissionsDe(role))
                    .as("rôle %s", role.getCode())
                    .contains(RolesPermissions.PATIENT_LIRE);
        }
    }

    @Test
    @DisplayName("aucune permission orpheline : la nomenclature est exactement l'union des rôles")
    void aucunePermissionOrpheline() {
        Set<String> accordees = RolesPermissions.permissionsAccordees();
        assertThat(accordees).isEqualTo(RolesPermissions.TOUTES_LES_PERMISSIONS);
        // La nomenclature est CLOSE : dix permissions, pas une de plus.
        assertThat(RolesPermissions.TOUTES_LES_PERMISSIONS).hasSize(10);
    }

    @Test
    @DisplayName("la matrice couvre les six rôles, chacun non vide")
    void matriceComplete() {
        assertThat(RolesPermissions.matrice()).containsOnlyKeys(RoleUtilisateur.values());
        for (RoleUtilisateur role : RoleUtilisateur.values()) {
            assertThat(RolesPermissions.permissionsDe(role))
                    .as("rôle %s", role.getCode())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("les permissions rendues sont immuables — personne n'étend la matrice en silence")
    void permissionsImmuables() {
        assertThatThrownBy(() -> RolesPermissions.permissionsDe(RoleUtilisateur.MEDECIN)
                .add("root:tout"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RolesPermissions.permissionsAccordees()
                .clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RolesPermissions.matrice()
                .put(RoleUtilisateur.ADMIN, Set.of("secret:gerer")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("permissionConnue sépare la nomenclature des inventions")
    void permissionsConnues() {
        assertThat(RolesPermissions.permissionConnue(RolesPermissions.ADMIN_GERER)).isTrue();
        assertThat(RolesPermissions.permissionConnue("root:tout")).isFalse();
        assertThat(RolesPermissions.permissionConnue("")).isFalse();
    }
}
