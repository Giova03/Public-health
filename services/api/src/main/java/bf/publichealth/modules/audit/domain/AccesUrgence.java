package bf.publichealth.modules.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Break-the-glass — accès d'urgence à un dossier patient.
 *
 * <p>Le principe (ADR break-the-glass) : l'accès d'urgence n'est JAMAIS
 * bloquant — il est TRACÉ et REVU. Une fenêtre de 30 minutes s'ouvre,
 * horodatée et motivée ; l'examen a posteriori clôt la boucle.</p>
 *
 * <p>Quand aucun contexte sécurité n'est actif (posture Sprint 0),
 * l'utilisateur est l'ANONYME matérialisé (patron V7 de sync.device) :
 * la colonne reste NOT NULL, la réponse API documente userId=null, et la
 * RLS de V10 ne matche jamais ce sentinel (fail-closed).</p>
 *
 * @param utilisateurId auteur de la brèche (ANONYME si non identifié)
 * @param ouvertA       horodatage d'ouverture
 * @param expireA       horodatage d'expiration (ouverture + 30 min)
 * @param revu          examen a posteriori effectué
 */
public record AccesUrgence(UUID id, UUID utilisateurId, UUID patientId, String raison,
                           Instant ouvertA, Instant expireA, boolean revu,
                           String commentaireRevue, Instant revuA) {

    /** Utilisateur anonyme — matérialisation du « sans contexte sécurité » (patron V7). */
    public static final UUID UTILISATEUR_ANONYME = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** L'identité publique de la réponse : null quand la brèche est anonyme. */
    public UUID utilisateurPublic() {
        return UTILISATEUR_ANONYME.equals(utilisateurId) ? null : utilisateurId;
    }

    /** La fenêtre est-elle passée ? (lecture : l'accès expiré apparaît expiré) */
    public boolean expire() {
        return Instant.now().isAfter(expireA);
    }
}
