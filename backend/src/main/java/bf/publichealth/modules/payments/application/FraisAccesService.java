package bf.publichealth.modules.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.payments.adapter.persistence.FraisAccesJdbc;
import bf.publichealth.modules.payments.adapter.persistence.FraisAccesJdbc.FraisAccesLue;

/**
 * Service frais d'accès — le parcours monétaire RÉEL du BF (I5, P0.5-6).
 *
 * <p>Admission → CAISSE → salle d'attente → consultation : le ticket
 * modérateur se règle AVANT l'acte clinique, et l'exonération (indigent
 * attesté, enfant de moins de 5 ans, césarienne, grossesse suivie) se
 * décide à la caisse, par un agent portant {@code paiement:initier}.
 * Règles :</p>
 * <ul>
 *   <li>un ticket par patient × structure × jour (UTC) — la re-demande
 *       du jour renvoie le ticket existant (200), jamais un doublon ;</li>
 *   <li>machine forward-only : {@code en_attente → paye | exonere},
 *       les deux états sont TERMINAUX — un ticket réglé ne se re-décide
 *       pas (409, même philosophie que les 8 états des paiements) ;</li>
 *   <li>l'exonération exige nature CONNUE + motif OBLIGATOIRE ;</li>
 *   <li>chaque décision est auditée (FRAIS_ACCES_CREATED /
 *       FRAIS_ACCES_ENCAISSE / FRAIS_ACCES_EXONERE) avec l'acteur du
 *       jeton, jamais du corps de requête ;</li>
 *   <li>la porte consultation (402) vit dans ConsultationService, qui
 *       appelle {@link #statutReglementAujourdhui} — communication
 *       inter-modules par service injecté (ADR-001).</li>
 * </ul>
 */
@Service
public class FraisAccesService {

    /** Natures d'exonération acceptées (miroir du CHECK V15). */
    public static final Set<String> NATURES_EXONERATION = Set.of(
            "indigent_atteste", "enfant_moins_5_ans", "cesarienne", "grossesse_suivie");

    private final FraisAccesJdbc fraisAcces;
    private final AuditRecorder audit;

    /** Requête invalide ou transition illégale (400/409 problem+json). */
    public static class FraisAccesInvalideException extends RuntimeException {
        public FraisAccesInvalideException(String message) {
            super(message);
        }
    }

    public FraisAccesService(FraisAccesJdbc fraisAcces, AuditRecorder audit) {
        this.fraisAcces = fraisAcces;
        this.audit = audit;
    }

    /** Ouvre le ticket d'accès du jour — idempotent (rejeu = ticket existant). */
    @Transactional
    public FraisAccesLue ouvrir(UUID patientId, UUID structureId, Long montantXof,
                                UUID acteur) {
        if (patientId == null) {
            throw new FraisAccesInvalideException("Le patient est obligatoire");
        }
        if (structureId == null) {
            throw new FraisAccesInvalideException("La structure est obligatoire");
        }
        long montant = montantXof == null ? 1000L : montantXof;
        if (montant < 0 || montant > 1_000_000L) {
            throw new FraisAccesInvalideException(
                    "Montant invalide (XOF, 0 à 1 000 000 attendus) : " + montant);
        }
        return fraisAcces.duJour(patientId, structureId).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            fraisAcces.inserer(id, patientId, structureId, montant, acteur, Instant.now());
            audit.record(acteur, "FRAIS_ACCES_CREATED", "frais_acces", id,
                    structureId, "ticket modérateur " + montant + " X CFA",
                    AuditEntryEntity.Result.SUCCESS,
                    Map.of("patientId", patientId.toString(), "montantXof", montant));
            return fraisAcces.trouver(id).orElseThrow();
        });
    }

    /** Encaisse le ticket en espèces — forward-only, terminal. */
    @Transactional
    public FraisAccesLue encaisser(UUID id, Long montantXof, UUID acteur) {
        FraisAccesLue ticket = ticket(id);
        if (!"en_attente".equals(ticket.statut())) {
            throw new FraisAccesInvalideException(
                    "Ticket déjà réglé (" + ticket.statut() + ") : en_attente → paye est forward-only");
        }
        long montant = montantXof == null ? ticket.montantXof() : montantXof;
        if (montant < 0 || montant > 1_000_000L) {
            throw new FraisAccesInvalideException(
                    "Montant invalide (XOF, 0 à 1 000 000 attendus) : " + montant);
        }
        fraisAcces.encaisser(id, montant, acteur, Instant.now());
        audit.record(acteur, "FRAIS_ACCES_ENCAISSE", "frais_acces", id,
                ticket.structureId(), "encaissement espèces " + montant + " X CFA",
                AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", ticket.patientId().toString(), "montantXof", montant));
        return fraisAcces.trouver(id).orElseThrow();
    }

    /** Exonère le ticket — nature CONNUE + motif OBLIGATOIRE, forward-only. */
    @Transactional
    public FraisAccesLue exonerer(UUID id, String nature, String motif, UUID acteur) {
        FraisAccesLue ticket = ticket(id);
        if (!"en_attente".equals(ticket.statut())) {
            throw new FraisAccesInvalideException(
                    "Ticket déjà réglé (" + ticket.statut() + ") : en_attente → exonere est forward-only");
        }
        if (nature == null || !NATURES_EXONERATION.contains(nature)) {
            throw new FraisAccesInvalideException(
                    "Nature d'exonération inconnue : " + nature
                            + " (attendu parmi " + NATURES_EXONERATION + ")");
        }
        if (motif == null || motif.isBlank()) {
            throw new FraisAccesInvalideException(
                    "Le motif d'exonération est OBLIGATOIRE (traçabilité — I5)");
        }
        fraisAcces.exonerer(id, nature, motif.trim(), acteur, Instant.now());
        audit.record(acteur, "FRAIS_ACCES_EXONERE", "frais_acces", id,
                ticket.structureId(), "exonération " + nature + " : " + motif.trim(),
                AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", ticket.patientId().toString(), "nature", nature));
        return fraisAcces.trouver(id).orElseThrow();
    }

    /** La porte consultation : statut réglé du jour (null/en_attente → refus). */
    @Transactional(readOnly = true)
    public String statutReglementAujourdhui(UUID patientId, UUID structureId) {
        return fraisAcces.statutDuJour(patientId, structureId);
    }

    @Transactional(readOnly = true)
    public List<FraisAccesLue> historique(UUID patientId, UUID structureId, String statut) {
        return fraisAcces.lister(patientId, structureId, statut);
    }

    @Transactional(readOnly = true)
    public List<FraisAccesLue> fileAttente(UUID structureId) {
        return fraisAcces.fileAttente(structureId);
    }

    @Transactional(readOnly = true)
    public FraisAccesLue trouver(UUID id) {
        return ticket(id);
    }

    private FraisAccesLue ticket(UUID id) {
        return fraisAcces.trouver(id)
                .orElseThrow(() -> new FraisAccesInvalideException("Ticket introuvable : " + id));
    }
}
