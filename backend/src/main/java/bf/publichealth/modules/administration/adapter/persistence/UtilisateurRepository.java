package bf.publichealth.modules.administration.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Accès aux utilisateurs du back-office.
 *
 * <p>Les filtres combinés (structureId, role, status) passent par les
 * {@link JpaSpecificationExecutor Specifications} — chaque filtre est
 * optionnel. La recherche par email est INSENSIBLE À LA CASSE (miroir
 * de l'index unique LOWER(email) de V12).</p>
 */
public interface UtilisateurRepository
        extends JpaRepository<UtilisateurEntity, UUID>, JpaSpecificationExecutor<UtilisateurEntity> {

    /** Idempotence d'invitation par email (unicité LOWER(email), V12). */
    Optional<UtilisateurEntity> findByEmailIgnoreCase(String email);

    /** Résolution du contexte : le porteur du compte Supabase. */
    Optional<UtilisateurEntity> findBySupabaseUserId(UUID supabaseUserId);
}
