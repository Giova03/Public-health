package bf.publichealth.modules.sync.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.identity.application.PatientService;
import bf.publichealth.modules.sync.adapter.persistence.SyncOpEntity;
import bf.publichealth.modules.sync.adapter.persistence.SyncOpRepository;
import bf.publichealth.modules.sync.adapter.persistence.SyncOutboxEntity;
import bf.publichealth.modules.sync.adapter.persistence.SyncOutboxRepository;

/**
 * Applique UNE op offline et journalise son résultat dans la MÊME
 * transaction (REQUIRES_NEW) : l'op idempotente (sync.op), le fait
 * métier, et la ligne d'outbox sont atomiques — le lot, lui, ne
 * s'arrête JAMAIS en bloc.
 *
 * <p>Routage par entité :
 * patient → {@link PatientService} du module identity (réutilisation
 * intégrale : idempotence par clientRequestId, 409 = contrat UX) ;
 * encounter/observation/condition → insertion append-only dans
 * clinical.* (UUID côté client, created_via='offline').</p>
 *
 * <p>Les échecs « sales » (FK, course concurrente) traversent la limite
 * de transaction — le rollback embarque l'op row — et c'est
 * {@link SyncService} qui journalise ensuite le rejet dans une
 * transaction fraîche. L'audit (REQUIRES_NEW, mécanisme payments)
 * survit volontairement au rollback : on trace l'échec, pas seulement
 * le succès.</p>
 */
@Service
public class SyncOpApplier {

    private static final Logger LOG = LoggerFactory.getLogger(SyncOpApplier.class);

    private final PatientService patientService;
    private final EcrivainClinique ecrivainClinique;
    private final MiroirPatients miroirPatients;
    private final SyncOpRepository opRepository;
    private final SyncOutboxRepository outboxRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public SyncOpApplier(PatientService patientService,
                         EcrivainClinique ecrivainClinique,
                         MiroirPatients miroirPatients,
                         SyncOpRepository opRepository,
                         SyncOutboxRepository outboxRepository,
                         AuditRecorder auditRecorder,
                         ObjectMapper objectMapper) {
        this.patientService = patientService;
        this.ecrivainClinique = ecrivainClinique;
        this.miroirPatients = miroirPatients;
        this.opRepository = opRepository;
        this.outboxRepository = outboxRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // patient — via PatientService (identity), réutilisation intégrale
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncService.ResultatOp appliquerPatient(UUID opId, UUID userId, UUID deviceId,
                                                   PatientService.CreatePatientCommand commande) {
        var creation = patientService.create(commande);
        var patient = creation.aggregate().patient();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("entityId", patient.getId());
        detail.put("phReference", patient.getPhReference());
        detail.put("replayed", creation.replayed());

        // Pas d'événement « created » pour un rejeu de clientRequestId :
        // le patient existe déjà, l'événement initial a déjà été émis.
        if (!creation.replayed()) {
            Map<String, Object> charge = new LinkedHashMap<>();
            charge.put("opId", opId);
            charge.put("deviceId", deviceId);
            charge.put("phReference", patient.getPhReference());
            ecrireOutbox("sync.patient.created", patient.getId(), charge);
        }
        // Audit : PatientService trace déjà PATIENT_CREATED (même
        // mécanisme AuditRecorder que payments) — pas de double entrée.
        return enregistrerOp(opId, userId, "patient", patient.getId(),
                SyncOpEntity.APPLIED, detail);
    }

    // ------------------------------------------------------------------
    // encounter / observation / condition — append-only dans clinical.*
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncService.ResultatOp appliquerEncounter(UUID opId, UUID userId, UUID deviceId,
                                                     UUID entityId,
                                                     EcrivainClinique.Encounter charge) {
        UUID patientId = resoudrePatient(charge.patientId());
        ecrivainClinique.insererEncounter(entityId, new EcrivainClinique.Encounter(
                patientId, charge.facilityId(), charge.practitionerId(),
                charge.encounterClass(), charge.reason(), charge.startedAt(), charge.endedAt()));

        ecrireOutbox("sync.encounter.created", entityId, evenement(opId, deviceId));
        auditer(userId, "encounter", entityId, opId, deviceId);
        return enregistrerOp(opId, userId, "encounter", entityId, SyncOpEntity.APPLIED,
                Map.of("entityId", entityId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncService.ResultatOp appliquerObservation(UUID opId, UUID userId, UUID deviceId,
                                                       UUID entityId,
                                                       EcrivainClinique.Observation charge) {
        UUID patientId = resoudrePatient(charge.patientId());
        ecrivainClinique.insererObservation(entityId, new EcrivainClinique.Observation(
                charge.encounterId(), patientId, charge.code(), charge.valueText(),
                charge.valueNum(), charge.status(), charge.effectiveAt()));

        ecrireOutbox("sync.observation.created", entityId, evenement(opId, deviceId));
        auditer(userId, "observation", entityId, opId, deviceId);
        return enregistrerOp(opId, userId, "observation", entityId, SyncOpEntity.APPLIED,
                Map.of("entityId", entityId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncService.ResultatOp appliquerCondition(UUID opId, UUID userId, UUID deviceId,
                                                     UUID entityId,
                                                     EcrivainClinique.Condition charge) {
        UUID patientId = resoudrePatient(charge.patientId());
        ecrivainClinique.insererCondition(entityId, new EcrivainClinique.Condition(
                charge.encounterId(), patientId, charge.code(), charge.clinicalStatus(),
                charge.recordedAt()));

        ecrireOutbox("sync.condition.created", entityId, evenement(opId, deviceId));
        auditer(userId, "condition", entityId, opId, deviceId);
        return enregistrerOp(opId, userId, "condition", entityId, SyncOpEntity.APPLIED,
                Map.of("entityId", entityId));
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    /**
     * Une opération clinique offline référence le patient par l'UUID de
     * son dossier LOCAL — qui est aussi sa clé d'idempotence
     * (clientRequestId). On le résout vers l'UUID serveur ; introuvable,
     * la valeur brute est conservée : patient_id est une référence lâche
     * (aucune FK inter-schemas par loi), aucune donnée clinique n'est
     * jamais perdue.
     */
    private UUID resoudrePatient(UUID patientIdBrut) {
        return miroirPatients.idPatientParClientRequestId(patientIdBrut)
                .orElse(patientIdBrut);
    }

    private Map<String, Object> evenement(UUID opId, UUID deviceId) {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("opId", opId);
        charge.put("deviceId", deviceId);
        return charge;
    }

    private void ecrireOutbox(String eventType, UUID aggregateId, Map<String, Object> charge) {
        try {
            outboxRepository.save(new SyncOutboxEntity(UuidV7.next(), eventType, aggregateId,
                    objectMapper.writeValueAsString(charge), Instant.now()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Charge outbox illisible pour " + eventType, e);
        }
    }

    /** Audit de l'op clinique appliquée — mêmes six dimensions que payments. */
    private void auditer(UUID userId, String entite, UUID entityId, UUID opId, UUID deviceId) {
        auditRecorder.record(userId, "SYNC_OP_APPLIED", entite, entityId, null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("opId", opId, "deviceId", deviceId == null ? "-" : deviceId.toString()));
    }

    private SyncService.ResultatOp enregistrerOp(UUID opId, UUID userId, String entite,
                                                 UUID entityId, String resultat,
                                                 Map<String, Object> detail) {
        try {
            SyncOpEntity entiteOp = opRepository.save(new SyncOpEntity(
                    opId, userId, entite, entityId, resultat, objectMapper.writeValueAsString(detail)));
            return SyncService.ResultatOp.depuis(entiteOp, objectMapper);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Détail de résultat illisible pour opId " + opId, e);
        }
    }
}
