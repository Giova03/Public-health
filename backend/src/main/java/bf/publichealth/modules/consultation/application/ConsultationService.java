package bf.publichealth.modules.consultation.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.consultation.adapter.persistence.ConsultationJdbc;

/**
 * Service consultation — l'ACTE CLINIQUE ENFIN PERSISTÉ (I4).
 *
 * <p>Motif, constantes (TA, température, poids), notes, diagnostic codé :
 * tout ce que le registre papier du CSPS contient vit désormais dans
 * clinical.encounter / observation / condition. Règles :</p>
 * <ul>
 *   <li>le patient doit exister et être VIVANT (I15 — décès scellé) ;</li>
 *   <li>idempotence offline par clientRequestId (rejeu = 200, paton V6) ;</li>
 *   <li>création auditée CONSULTATION_CREATED, avec l'acteur du jeton ;</li>
 *   <li>append-only : une erreur se corrige par contre-entrée
 *       entered-in-error (garde SQL V3), jamais par réécriture.</li>
 * </ul>
 */
@Service
public class ConsultationService {

    private final ConsultationJdbc consultations;
    private final AuditRecorder audit;

    /** Commande de création (l'acteur vient du jeton, jamais du corps). */
    public record CommandeCreation(UUID clientRequestId, UUID patientId, UUID facilityId,
                                   UUID practitionerId, String motif,
                                   String diagnosticCode, String diagnosticLabel,
                                   String notes, Map<String, Double> constantes) {
    }

    public static class PatientInvalideException extends RuntimeException {
        public PatientInvalideException(String message) {
            super(message);
        }
    }

    public ConsultationService(ConsultationJdbc consultations, AuditRecorder audit) {
        this.consultations = consultations;
        this.audit = audit;
    }

    @Transactional
    public ConsultationJdbc.ConsultationLue creer(CommandeCreation commande, UUID acteur) {
        String etat = consultations.etatPatient(commande.patientId());
        switch (etat) {
            case "introuvable" -> throw new PatientInvalideException(
                    "Patient introuvable : " + commande.patientId());
            case "decede" -> throw new PatientInvalideException(
                    "Dossier scellé : décès déclaré — aucune consultation possible");
            default -> { /* patient actif */ }
        }
        if (commande.motif() == null || commande.motif().isBlank()) {
            throw new PatientInvalideException("Le motif de consultation est obligatoire");
        }
        if (commande.diagnosticCode() == null || commande.diagnosticCode().isBlank()) {
            throw new PatientInvalideException("Le diagnostic CODÉ est obligatoire (I14)");
        }

        UUID id = UUID.randomUUID();
        List<ConsultationJdbc.Constante> constantes = commande.constantes() == null
                ? List.of()
                : commande.constantes().entrySet().stream()
                        .filter(e -> e.getValue() != null)
                        .map(e -> new ConsultationJdbc.Constante(e.getKey(), e.getValue()))
                        .toList();

        consultations.inserer(id, commande.patientId(), commande.facilityId(),
                commande.practitionerId(), commande.motif().trim(),
                commande.diagnosticCode(), commande.diagnosticLabel(),
                commande.notes(), constantes, Instant.now());

        audit.record(acteur, "CONSULTATION_CREATED", "consultation", id,
                commande.facilityId(), commande.diagnosticCode(),
                AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", commande.patientId().toString(),
                       "motif", commande.motif().trim()));

        return consultations.trouver(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ConsultationJdbc.ConsultationLue> parPatient(UUID patientId) {
        return consultations.listerParPatient(patientId);
    }

    @Transactional(readOnly = true)
    public ConsultationJdbc.ConsultationLue trouver(UUID id) {
        return consultations.trouver(id)
                .orElseThrow(() -> new PatientInvalideException("Consultation introuvable : " + id));
    }
}
