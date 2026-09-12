package bf.publichealth.modules.reference.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.reference.adapter.persistence.ReferenceJdbc;

/**
 * Service référence / contre-référence (V14, I7).
 *
 * <p>Machine à états : envoyee → recue → hospitalisee (option) →
 * retournee (contre-référence, résumé OBLIGATOIRE). Les statuts
 * envoyee/recue/hospitalisee vivent tant que le patient est dans
 * la filière ; retournee clôt. Chaque transition est auditée.</p>
 */
@Service
public class ReferenceService {

    private final ReferenceJdbc fiches;
    private final AuditRecorder audit;

    public ReferenceService(ReferenceJdbc fiches, AuditRecorder audit) {
        this.fiches = fiches;
        this.audit = audit;
    }

    public static class ReferenceRefuseeException extends RuntimeException {
        public ReferenceRefuseeException(String message) {
            super(message);
        }
    }

    public record CommandeCreation(UUID patientId, UUID structureOrigine,
                                   UUID structureDestination, String motif, boolean urgence,
                                   UUID clientRequestId) {
    }

    @Transactional
    public ReferenceJdbc.Fiche creer(CommandeCreation commande, UUID acteur) {
        if (commande.structureOrigine().equals(commande.structureDestination())) {
            throw new ReferenceRefuseeException(
                    "La référence doit changer de structure (origine ≠ destination)");
        }
        UUID id = UUID.randomUUID();
        fiches.inserer(id, commande.patientId(), commande.structureOrigine(),
                commande.structureDestination(), commande.motif().trim(), commande.urgence(),
                commande.clientRequestId(), acteur);
        audit.record(acteur, "REFERENCE_CREATED", "reference", id,
                commande.structureOrigine(), commande.motif().trim(),
                AuditEntryEntity.Result.SUCCESS,
                java.util.Map.of("destination", commande.structureDestination().toString(),
                        "patientId", commande.patientId().toString(),
                        "urgence", commande.urgence()));
        return fiches.trouver(id).orElseThrow();
    }

    @Transactional
    public ReferenceJdbc.Fiche recevoir(UUID id, UUID acteur) {
        ReferenceJdbc.Fiche fiche = charger(id);
        exiger(fiche, "recue", "envoyee");
        fiches.majStatut(id, "recue");
        audit.record(acteur, "REFERENCE_RECEPTION", "reference", id,
                fiche.structureDestination(), "envoyee→recue",
                AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    @Transactional
    public ReferenceJdbc.Fiche hospitaliser(UUID id, UUID acteur) {
        ReferenceJdbc.Fiche fiche = charger(id);
        exiger(fiche, "hospitalisee", "recue");
        fiches.majStatut(id, "hospitalisee");
        audit.record(acteur, "REFERENCE_HOSPITALISATION", "reference", id,
                fiche.structureDestination(), "recue→hospitalisee",
                AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    @Transactional
    public ReferenceJdbc.Fiche contreReferencer(UUID id, String resume, UUID acteur) {
        if (resume == null || resume.isBlank()) {
            throw new ReferenceRefuseeException(
                    "Le résumé de contre-référence est OBLIGATOIRE (la boucle se referme)");
        }
        ReferenceJdbc.Fiche fiche = charger(id);
        if (!"recue".equals(fiche.statut()) && !"hospitalisee".equals(fiche.statut())) {
            throw new ReferenceRefuseeException(
                    "Contre-référence impossible depuis le statut " + fiche.statut());
        }
        fiches.enregistrerContreReference(id, resume.trim(), acteur);
        audit.record(acteur, "CONTRE_REFERENCE", "reference", id,
                fiche.structureOrigine(), resume.trim(),
                AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    @Transactional(readOnly = true)
    public List<ReferenceJdbc.Fiche> lister(UUID patientId, UUID structureId, String statut) {
        return fiches.lister(patientId, structureId, statut);
    }

    @Transactional(readOnly = true)
    public ReferenceJdbc.Fiche trouver(UUID id) {
        return charger(id);
    }

    private ReferenceJdbc.Fiche charger(UUID id) {
        return fiches.trouver(id)
                .orElseThrow(() -> new ReferenceRefuseeException("Référence introuvable : " + id));
    }

    private static void exiger(ReferenceJdbc.Fiche fiche, String cible, String attendu) {
        if (!attendu.equals(fiche.statut())) {
            throw new ReferenceRefuseeException(
                    "Transition illégale : " + fiche.statut() + " → " + cible);
        }
    }
}
