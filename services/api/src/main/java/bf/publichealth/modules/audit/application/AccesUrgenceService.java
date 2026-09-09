package bf.publichealth.modules.audit.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AccesUrgenceEntity;
import bf.publichealth.modules.audit.adapter.persistence.AccesUrgenceRepository;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.domain.AccesUrgence;
import bf.publichealth.modules.audit.domain.AccesUrgenceIntrouvableException;

/**
 * Cas d'usage break-the-glass — l'accès d'urgence n'est JAMAIS bloquant,
 * il est TRACÉ (entrée d'audit chaînée) et REVU (examen a posteriori).
 *
 * <p>Chaque ouverture et chaque revue scellent le journal d'audit
 * (action BREAK_THE_GLASS / BREAK_THE_GLASS_REVIEW, raison/commentaire
 * obligatoires) — le chaînage par hachage de V5 rend toute altération
 * rétroactive détectable.</p>
 */
@Service
public class AccesUrgenceService {

    /** Fenêtre d'urgence : 30 minutes après l'ouverture. */
    public static final Duration FENETRE = Duration.ofMinutes(30);

    private final AccesUrgenceRepository repository;
    private final AuditRecorder auditRecorder;

    public AccesUrgenceService(AccesUrgenceRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Ouvre une brèche : horodatée, motivée, expirant à ouverture + 30 min.
     *
     * @param utilisateur auteur depuis le contexte sécurité (null = anonyme,
     *                    sentinel NOT NULL en base — voir V10)
     */
    @Transactional
    public AccesUrgence ouvrir(UUID patientId, String raison, UUID utilisateur) {
        UUID id = UuidV7.next();
        Instant maintenant = Instant.now();
        UUID auteur = utilisateur == null ? AccesUrgence.UTILISATEUR_ANONYME : utilisateur;

        AccesUrgenceEntity entite = new AccesUrgenceEntity(
                id, auteur, patientId, raison, maintenant, maintenant.plus(FENETRE));
        repository.save(entite);

        auditRecorder.record(auteur, "BREAK_THE_GLASS", "emergency_access", id,
                null, raison, AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", patientId.toString(),
                       "expiresAt", entite.getExpiresAt().toString()));
        return versDomaine(entite);
    }

    /** Lecture : historique d'un patient, ou file des brèches non revues. */
    @Transactional(readOnly = true)
    public List<AccesUrgence> lister(UUID patientId, boolean enAttente) {
        List<AccesUrgenceEntity> entites = enAttente || patientId == null
                ? repository.findByReviewedFalseOrderByOpenedAtDesc()
                : repository.findByPatientIdOrderByOpenedAtDesc(patientId);
        return entites.stream().map(AccesUrgenceService::versDomaine).toList();
    }

    /** Examen a posteriori : marque la revue, la clôture est traçable. */
    @Transactional
    public AccesUrgence revoir(UUID idAcces, String commentaire, UUID relecteur) {
        AccesUrgenceEntity entite = repository.findById(idAcces)
                .orElseThrow(() -> new AccesUrgenceIntrouvableException(idAcces));
        entite.marquerRevu(commentaire, Instant.now());
        repository.save(entite);

        auditRecorder.record(relecteur, "BREAK_THE_GLASS_REVIEW", "emergency_access", idAcces,
                null, commentaire, AuditEntryEntity.Result.SUCCESS,
                Map.of("reviewedAt", entite.getReviewedAt().toString()));
        return versDomaine(entite);
    }

    private static AccesUrgence versDomaine(AccesUrgenceEntity entite) {
        return new AccesUrgence(entite.getId(), entite.getUtilisateurId(), entite.getPatientId(),
                entite.getReason(), entite.getOpenedAt(), entite.getExpiresAt(),
                entite.isReviewed(), entite.getReviewComment(), entite.getReviewedAt());
    }
}
