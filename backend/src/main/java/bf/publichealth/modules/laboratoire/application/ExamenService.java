package bf.publichealth.modules.laboratoire.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.laboratoire.adapter.persistence.ExamenJdbc;

/**
 * Service laboratoire (V14, I6) — la boucle TDR → résultat → diagnostic.
 *
 * <p>Un examen DEMANDÉ n'a pas de résultat ; un résultat ne se réécrit
 * JAMAIS (saisi une fois, statut demande→resultat, terminaux). Un
 * diagnostic « paludisme » sans TDR enregistré reste possible pour
 * l'historique, mais la plateforme fournit désormais la PREUVE
 * consultable qui manquait (audit : opinion non étayée).</p>
 */
@Service
public class ExamenService {

    private final ExamenJdbc examens;
    private final AuditRecorder audit;

    public static class ExamenRefuseException extends RuntimeException {
        public ExamenRefuseException(String message) {
            super(message);
        }
    }

    private static final List<String> TYPES_VALIDES = List.of(
            "tdr_paludisme", "goutte_epaisse", "nfs", "glycemie", "urine", "hiv",
            "syphilis", "autre");

    public ExamenService(ExamenJdbc examens, AuditRecorder audit) {
        this.examens = examens;
        this.audit = audit;
    }

    public record CommandeCreation(UUID patientId, UUID consultationId, UUID structureId,
                                   String type) {
    }

    @Transactional
    public ExamenJdbc.Examen creer(CommandeCreation commande, UUID acteur) {
        if (commande.patientId() == null) {
            throw new ExamenRefuseException("Le patient est obligatoire");
        }
        if (commande.type() == null || !TYPES_VALIDES.contains(commande.type())) {
            throw new ExamenRefuseException(
                    "Type d'examen inconnu : " + commande.type() + " (attendu parmi " + TYPES_VALIDES + ")");
        }
        UUID id = UUID.randomUUID();
        examens.inserer(id, commande.patientId(), commande.consultationId(),
                commande.structureId(), commande.type(), acteur);
        audit.record(acteur, "EXAMEN_DEMANDE", "examen", id, commande.structureId(),
                commande.type(), AuditEntryEntity.Result.SUCCESS,
                java.util.Map.of("patientId", commande.patientId().toString()));
        return examens.trouver(id).orElseThrow();
    }

    @Transactional
    public ExamenJdbc.Examen enregistrerResultat(UUID id, String resultatText,
                                                 Boolean resultatPositif, UUID acteur) {
        ExamenJdbc.Examen examen = examens.trouver(id)
                .orElseThrow(() -> new ExamenRefuseException("Examen introuvable : " + id));
        if (!"demande".equals(examen.statut())) {
            throw new ExamenRefuseException("Résultat déjà enregistré — un examen ne se réécrit jamais");
        }
        if (resultatText == null || resultatText.isBlank()) {
            throw new ExamenRefuseException("Le résultat (texte) est obligatoire");
        }
        examens.enregistrerResultat(id, resultatText.trim(), resultatPositif, acteur);
        audit.record(acteur, "EXAMEN_RESULTAT", "examen", id, examen.structureId(),
                resultatText.trim(), AuditEntryEntity.Result.SUCCESS,
                java.util.Map.of("positif", String.valueOf(resultatPositif)));
        return examens.trouver(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ExamenJdbc.Examen> lister(UUID patientId, String statut) {
        return examens.lister(patientId, statut);
    }

    @Transactional(readOnly = true)
    public ExamenJdbc.Examen trouver(UUID id) {
        return examens.trouver(id)
                .orElseThrow(() -> new ExamenRefuseException("Examen introuvable : " + id));
    }
}
