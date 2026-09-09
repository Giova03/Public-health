package bf.publichealth.modules.administration.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cartographie rôle → permissions — domaine PUR de l'épique E6.
 *
 * <p>Matrice exhaustive et close : chaque permission est accordée à
 * au moins un rôle (aucune orpheline), chaque rôle porte au moins
 * la lecture du MPI ({@code patient:lire} — tout soignant identifie
 * le patient avant d'agir). Le miroir persisté
 * {@code administration.role_permission} est semé à l'IDENTIQUE par
 * la migration V12 — BackofficeIT verrouille l'égalité table ↔
 * domaine pour empêcher toute dérive silencieuse.</p>
 *
 * <p>Permissions retenues (liste minimale cohérente avec les modules
 * livrés E1→E7) :</p>
 * <ul>
 *   <li>{@code patient:lire} — recherche/lecture MPI + façade FHIR (E1, E7)</li>
 *   <li>{@code patient:ecrire} — création/forced-creation du dossier (E1)</li>
 *   <li>{@code prescription:lire} — dossier pharmacologique (E3, E7)</li>
 *   <li>{@code prescription:ecrire} — prescrire (E3)</li>
 *   <li>{@code dispenser} — dispensation au comptoir (E3)</li>
 *   <li>{@code paiement:initier} — initiation FedaPay (E2/E4)</li>
 *   <li>{@code paiement:lire} — suivi des paiements et factures (E4)</li>
 *   <li>{@code paiement:reconcilier} — réconciliation (run qui fait foi, E4)</li>
 *   <li>{@code audit:lire} — journal d'audit et brèches (E5)</li>
 *   <li>{@code admin:gerer} — back-office : structures, utilisateurs, rôles (E6)</li>
 * </ul>
 */
public final class RolesPermissions {

    private RolesPermissions() {
    }

    // ------------------------------------------------------------------
    // Les dix permissions (nomenclature close)
    // ------------------------------------------------------------------

    public static final String PATIENT_LIRE = "patient:lire";
    public static final String PATIENT_ECRIRE = "patient:ecrire";
    public static final String PRESCRIPTION_LIRE = "prescription:lire";
    public static final String PRESCRIPTION_ECRIRE = "prescription:ecrire";
    public static final String DISPENSER = "dispenser";
    public static final String PAIEMENT_INITIER = "paiement:initier";
    public static final String PAIEMENT_LIRE = "paiement:lire";
    public static final String PAIEMENT_RECONCILIER = "paiement:reconcilier";
    public static final String AUDIT_LIRE = "audit:lire";
    public static final String ADMIN_GERER = "admin:gerer";

    /** Toutes les permissions de la nomenclature — close, testée sans orpheline. */
    public static final Set<String> TOUTES_LES_PERMISSIONS = Set.of(
            PATIENT_LIRE, PATIENT_ECRIRE,
            PRESCRIPTION_LIRE, PRESCRIPTION_ECRIRE, DISPENSER,
            PAIEMENT_INITIER, PAIEMENT_LIRE, PAIEMENT_RECONCILIER,
            AUDIT_LIRE, ADMIN_GERER);

    // ------------------------------------------------------------------
    // La matrice (l'ordre d'insertion est celui du semis V12)
    // ------------------------------------------------------------------

    private static final Map<RoleUtilisateur, Set<String>> MATRICE = construireMatrice();

    private static Map<RoleUtilisateur, Set<String>> construireMatrice() {
        Map<RoleUtilisateur, Set<String>> matrice = new LinkedHashMap<>();

        // admin : tout le back-office + supervision globale.
        matrice.put(RoleUtilisateur.ADMIN, Set.of(
                PATIENT_LIRE, PATIENT_ECRIRE,
                PRESCRIPTION_LIRE, PRESCRIPTION_ECRIRE, DISPENSER,
                PAIEMENT_INITIER, PAIEMENT_LIRE, PAIEMENT_RECONCILIER,
                AUDIT_LIRE, ADMIN_GERER));

        // medecin : dossier patient + prescription.
        matrice.put(RoleUtilisateur.MEDECIN, Set.of(
                PATIENT_LIRE, PATIENT_ECRIRE,
                PRESCRIPTION_LIRE, PRESCRIPTION_ECRIRE));

        // infirmier : admission MPI + frais d'accès au comptoir (CSPS).
        matrice.put(RoleUtilisateur.INFIRMIER, Set.of(
                PATIENT_LIRE, PATIENT_ECRIRE, PAIEMENT_INITIER));

        // pharmacien : dispensation.
        matrice.put(RoleUtilisateur.PHARMACIEN, Set.of(
                PATIENT_LIRE, PRESCRIPTION_LIRE, DISPENSER));

        // agent_financier : paiements et facturation.
        matrice.put(RoleUtilisateur.AGENT_FINANCIER, Set.of(
                PATIENT_LIRE, PAIEMENT_INITIER, PAIEMENT_LIRE));

        // superviseur : supervision en lecture (paiements, audit).
        matrice.put(RoleUtilisateur.SUPERVISEUR, Set.of(
                PATIENT_LIRE, PRESCRIPTION_LIRE, PAIEMENT_LIRE, AUDIT_LIRE));

        return Collections.unmodifiableMap(matrice);
    }

    /** Permissions du rôle — immuable, jamais null (rôle inconnu → IllegalArgumentException). */
    public static Set<String> permissionsDe(RoleUtilisateur role) {
        Set<String> permissions = MATRICE.get(role);
        if (permissions == null) {
            throw new IllegalArgumentException("Rôle sans cartographie de permissions : " + role);
        }
        return permissions;
    }

    /** Vérifie qu'une permission appartient bien à la nomenclature. */
    public static boolean permissionConnue(String permission) {
        return TOUTES_LES_PERMISSIONS.contains(permission);
    }

    /** La matrice complète — immuable (pour les contrôles de cohérence). */
    public static Map<RoleUtilisateur, Set<String>> matrice() {
        return MATRICE;
    }

    /** Toutes les permissions effectivement accordées (union des rôles). */
    public static Set<String> permissionsAccordees() {
        Set<String> accordees = new LinkedHashSet<>();
        MATRICE.values().forEach(accordees::addAll);
        return Collections.unmodifiableSet(accordees);
    }
}
