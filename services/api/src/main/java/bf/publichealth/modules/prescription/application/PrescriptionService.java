package bf.publichealth.modules.prescription.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.prescription.adapter.persistence.DispensationEntity;
import bf.publichealth.modules.prescription.adapter.persistence.DispensationRepository;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionEntity;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionItemEntity;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionItemRepository;
import bf.publichealth.modules.prescription.adapter.persistence.PrescriptionRepository;
import bf.publichealth.modules.prescription.domain.DispensingRules;
import bf.publichealth.modules.prescription.domain.IllegalPrescriptionTransitionException;
import bf.publichealth.modules.prescription.domain.PatientIntrouvableException;
import bf.publichealth.modules.prescription.domain.PrescriptionInactiveException;
import bf.publichealth.modules.prescription.domain.PrescriptionIntrouvableException;
import bf.publichealth.modules.prescription.domain.QuantityExceededException;
import bf.publichealth.modules.prescription.domain.StatutPrescription;

/**
 * Cas d'usage prescription & dispensation — épique E3.
 *
 * <p>Append-only par conception : une prescription ne se réécrit jamais,
 * elle s'annule par contre-entrée (cancelled / entered-in-error, motif
 * OBLIGATOIRE). La dispensation est partielle et CUMULÉE — le domaine
 * (DispensingRules) fait foi pour le restant et le dépassement. Chaque
 * écriture et chaque refus porte son entrée d'audit (patron payments :
 * six dimensions, chaînage, DENIED survivant au rollback).</p>
 */
@Service
public class PrescriptionService {

    // ------------------------------------------------------------------
    // Entrées / sorties (contrats stables, loi n°5 des modules)
    // ------------------------------------------------------------------

    public record LigneCommande(String medicationCode, String medicationLabel, String dose,
                                String form, String route, String frequency,
                                Integer durationDays, BigDecimal quantityPrescribed) {
    }

    public record CommandeCreation(UUID id, UUID patientId, UUID encounterId, UUID prescriberId,
                                   UUID facilityId, UUID clientRequestId, String createdVia,
                                   Instant issuedAt, List<LigneCommande> lignes) {
    }

    /** Ligne enrichie du cumul dispensé et du restant (le comptoir lit ici). */
    public record LigneDetail(PrescriptionItemEntity ligne, BigDecimal quantiteDispensee,
                              BigDecimal restant) {
    }

    public record PrescriptionDetail(PrescriptionEntity prescription, List<LigneDetail> lignes,
                                     List<DispensationEntity> dispensations) {
    }

    public record CreationPrescription(PrescriptionDetail detail, boolean rejouee) {
    }

    public record DispensationResultat(DispensationEntity dispensation, boolean rejouee,
                                       BigDecimal restant) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PrescriptionService.class);

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionItemRepository itemRepository;
    private final DispensationRepository dispensationRepository;
    private final PatientLookup patientLookup;
    private final AuditRecorder auditRecorder;

    public PrescriptionService(PrescriptionRepository prescriptionRepository,
                               PrescriptionItemRepository itemRepository,
                               DispensationRepository dispensationRepository,
                               PatientLookup patientLookup,
                               AuditRecorder auditRecorder) {
        this.prescriptionRepository = prescriptionRepository;
        this.itemRepository = itemRepository;
        this.dispensationRepository = dispensationRepository;
        this.patientLookup = patientLookup;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Création — idempotente + patient vérifié via le module identity
    // ------------------------------------------------------------------

    /**
     * Création idempotente : une clé client (client_request_id) = une
     * prescription, jamais deux — le rejeu réseau rend la MÊME
     * prescription (200), même si le réseau l'a déjà validée.
     */
    @Transactional
    public CreationPrescription creer(CommandeCreation commande) {
        // 1. Idempotence offline-first (patron V6).
        if (commande.clientRequestId() != null) {
            var existant = prescriptionRepository.findByClientRequestId(commande.clientRequestId());
            if (existant.isPresent()) {
                audit(commande.prescriberId(), "PRESCRIPTION_CREATED", "prescription",
                        existant.get().getId(), commande.facilityId(),
                        "REJEU_CLIENT_REQUEST_ID", AuditEntryEntity.Result.SUCCESS,
                        Map.of("replayed", true));
                return new CreationPrescription(trouver(existant.get().getId()), true);
            }
        }
        // Filet de sécurité : un UUID v7 fourni par le client et déjà connu
        // est un rejeu (la PWA rejoue la même création, clé en moins).
        if (commande.id() != null) {
            var existant = prescriptionRepository.findById(commande.id());
            if (existant.isPresent()) {
                audit(commande.prescriberId(), "PRESCRIPTION_CREATED", "prescription",
                        existant.get().getId(), commande.facilityId(),
                        "REJEU_UUID_CLIENT_DEJA_CONNU", AuditEntryEntity.Result.SUCCESS,
                        Map.of("replayed", true));
                return new CreationPrescription(trouver(existant.get().getId()), true);
            }
        }

        // 2. Loi n°3 : patient_id sans FK — l'existence est portée par
        //    l'application via le port vers identity. Sans patient, rien.
        if (!patientLookup.patientActif(commande.patientId())) {
            audit(commande.prescriberId(), "PRESCRIPTION_REFUSED", "prescription", null,
                    commande.facilityId(), "PATIENT_INCONNU", AuditEntryEntity.Result.DENIED,
                    Map.of("patientId", commande.patientId()));
            throw new PatientIntrouvableException(commande.patientId());
        }

        // 3. Invariant de domaine : une prescription porte au moins une ligne.
        if (commande.lignes() == null || commande.lignes().isEmpty()) {
            throw new IllegalArgumentException(
                    "Une prescription porte au moins une ligne de médicament");
        }

        // 4. Insertion append-only : prescription puis ses lignes.
        PrescriptionEntity prescription = new PrescriptionEntity(
                commande.id() != null ? commande.id() : UuidV7.next(),
                commande.patientId(), commande.encounterId(), commande.prescriberId(),
                commande.facilityId(),
                commande.issuedAt() != null ? commande.issuedAt() : Instant.now(),
                commande.createdVia(), commande.clientRequestId());
        prescriptionRepository.save(prescription);

        for (LigneCommande ligne : commande.lignes()) {
            itemRepository.save(new PrescriptionItemEntity(UuidV7.next(), prescription.getId(),
                    ligne.medicationCode(), ligne.medicationLabel(), ligne.dose(), ligne.form(),
                    ligne.route(), ligne.frequency(), ligne.durationDays(),
                    ligne.quantityPrescribed()));
        }

        audit(commande.prescriberId(), "PRESCRIPTION_CREATED", "prescription",
                prescription.getId(), commande.facilityId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", commande.patientId(), "lignes", commande.lignes().size(),
                        "createdVia", prescription.getCreatedVia()));

        return new CreationPrescription(trouver(prescription.getId()), false);
    }

    // ------------------------------------------------------------------
    // Annulation & contre-entrée — jamais de réécriture, jamais de DELETE
    // ------------------------------------------------------------------

    /** Annulation logistique : seul ACTIVE → cancelled, motif OBLIGATOIRE. */
    @Transactional
    public PrescriptionDetail annuler(UUID id, String motif) {
        exigerMotif(motif);
        PrescriptionEntity prescription = charger(id);
        exigerContreEntree(prescription, StatutPrescription.CANCELLED);

        prescription.annuler(motif);
        prescriptionRepository.save(prescription);

        audit(prescription.getPrescriberId(), "PRESCRIPTION_CANCELLED", "prescription",
                prescription.getId(), prescription.getFacilityId(), motif,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", StatutPrescription.ACTIVE.getCode(),
                        "to", StatutPrescription.CANCELLED.getCode()));
        return trouver(id);
    }

    /**
     * Contre-entrée d'erreur clinique de saisie (entered-in-error) :
     * l'équivalent médical de l'annulation — l'historique reste, le
     * DELETE n'existe pas.
     */
    @Transactional
    public PrescriptionDetail passerEnErreur(UUID id, String motif) {
        exigerMotif(motif);
        PrescriptionEntity prescription = charger(id);
        exigerContreEntree(prescription, StatutPrescription.ENTERED_IN_ERROR);

        prescription.passerEnErreur(motif);
        prescriptionRepository.save(prescription);

        audit(prescription.getPrescriberId(), "PRESCRIPTION_ENTERED_IN_ERROR", "prescription",
                prescription.getId(), prescription.getFacilityId(), motif,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", StatutPrescription.ACTIVE.getCode(),
                        "to", StatutPrescription.ENTERED_IN_ERROR.getCode()));
        return trouver(id);
    }

    /** Une contre-entrée ne se fait jamais en silence : le motif est OBLIGATOIRE. */
    private void exigerMotif(String motif) {
        if (motif == null || motif.isBlank()) {
            throw new IllegalArgumentException(
                    "Le motif de la contre-entrée (annulation ou erreur de saisie) est OBLIGATOIRE");
        }
    }

    /** Transition validée par le domaine ; toute tentative illégale est tracée DENIED. */
    private void exigerContreEntree(PrescriptionEntity prescription, StatutPrescription cible) {
        try {
            prescription.getStatut().exigerTransitionVers(cible);
        } catch (IllegalPrescriptionTransitionException e) {
            audit(prescription.getPrescriberId(), "PRESCRIPTION_TRANSITION_DENIED",
                    "prescription", prescription.getId(), prescription.getFacilityId(),
                    "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", e.getFrom().getCode(), "to", e.getTo().getCode()));
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Dispensation — partielle, cumulée, idempotente
    // ------------------------------------------------------------------

    /**
     * Enregistre UNE dispensation partielle. Plusieurs dispensations se
     * cumulent par ligne jusqu'à épuisement de la quantité prescrite :
     * le dépassement est refusé (409 avec restant + demandé), la
     * prescription inactive est refusée (409). Le rejeu d'une clé client
     * est idempotent (200, la MÊME dispensation).
     */
    @Transactional
    public DispensationResultat dispenser(UUID prescriptionId, UUID ligneId, BigDecimal quantite,
                                          UUID clientRequestId, UUID dispensedBy) {
        // 1. Idempotence offline-first : rejeu = la même dispensation,
        //    même si la prescription a été annulée entre-temps (le fait
        //    historique déjà enregistré ne se réécrit pas).
        if (clientRequestId != null) {
            var existante = dispensationRepository.findByClientRequestId(clientRequestId);
            if (existante.isPresent()) {
                DispensationEntity dispensation = existante.get();
                BigDecimal restant = restantActuel(dispensation.getItemId());
                audit(dispensedBy, "DISPENSATION_RECORDED", "dispensation", dispensation.getId(),
                        null, "REJEU_CLIENT_REQUEST_ID", AuditEntryEntity.Result.SUCCESS,
                        Map.of("replayed", true, "prescriptionId", prescriptionId));
                return new DispensationResultat(dispensation, true, restant);
            }
        }

        PrescriptionEntity prescription = charger(prescriptionId);
        PrescriptionItemEntity ligne = itemRepository
                .findByIdAndPrescriptionId(ligneId, prescriptionId)
                .orElseThrow(() -> new PrescriptionIntrouvableException(
                        "Ligne de prescription introuvable : %s (prescription %s)"
                                .formatted(ligneId, prescriptionId)));

        // 2. Le domaine fait foi : prescription active, quantité positive,
        //    cumul + demandé ≤ prescrit.
        BigDecimal cumul = cumulPourLigne(ligne.getId());
        try {
            DispensingRules.verifierAvantDispensation(prescription.getStatut(),
                    ligne.getQuantityPrescribed(), cumul, quantite);
        } catch (PrescriptionInactiveException | QuantityExceededException e) {
            // Le refus vaut de l'or : trace DENIED en REQUIRES_NEW, qui
            // survit au rollback de la transaction métier.
            String raison = e instanceof PrescriptionInactiveException
                    ? "PRESCRIPTION_INACTIVE" : "DEPASSEMENT_QUANTITE";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("prescriptionId", prescriptionId);
            details.put("itemId", ligneId);
            if (e instanceof QuantityExceededException depassement) {
                details.put("restant", depassement.getRestant());
                details.put("demande", depassement.getDemandee());
            } else {
                details.put("statut", prescription.getStatut().getCode());
            }
            audit(dispensedBy, "DISPENSATION_DENIED", "dispensation", null,
                    prescription.getFacilityId(), raison, AuditEntryEntity.Result.DENIED, details);
            LOG.warn("Dispensation refusée prescription={} ligne={} raison={}",
                    prescriptionId, ligneId, raison);
            throw e;
        }

        // 3. Le fait accompli : insertion append-only, cumul mis à jour.
        DispensationEntity dispensation = dispensationRepository.save(
                new DispensationEntity(UuidV7.next(), prescriptionId, ligneId, quantite,
                        dispensedBy, clientRequestId));
        BigDecimal restantApres = DispensingRules.restant(ligne.getQuantityPrescribed(),
                cumul.add(quantite));

        audit(dispensedBy, "DISPENSATION_RECORDED", "dispensation", dispensation.getId(),
                prescription.getFacilityId(), null, AuditEntryEntity.Result.SUCCESS,
                Map.of("prescriptionId", prescriptionId, "itemId", ligneId,
                        "quantite", quantite, "restant", restantApres));

        return new DispensationResultat(dispensation, false, restantApres);
    }

    // ------------------------------------------------------------------
    // Lecture — dossier pharmacologique
    // ------------------------------------------------------------------

    /** Vue complète : prescription + lignes (cumul, restant) + dispensations. */
    @Transactional(readOnly = true)
    public PrescriptionDetail trouver(UUID id) {
        PrescriptionEntity prescription = charger(id);
        List<PrescriptionItemEntity> lignes = itemRepository
                .findByPrescriptionIdOrderByIdAsc(id);
        List<DispensationEntity> dispensations = dispensationRepository
                .findByPrescriptionIdOrderByIdAsc(id);

        Map<UUID, BigDecimal> cumuls = new LinkedHashMap<>();
        for (DispensationEntity d : dispensations) {
            cumuls.merge(d.getItemId(), d.getQuantity(), BigDecimal::add);
        }
        List<LigneDetail> detailLignes = new ArrayList<>();
        for (PrescriptionItemEntity ligne : lignes) {
            BigDecimal cumul = cumuls.getOrDefault(ligne.getId(), BigDecimal.ZERO);
            detailLignes.add(new LigneDetail(ligne, cumul,
                    DispensingRules.restant(ligne.getQuantityPrescribed(), cumul)));
        }
        return new PrescriptionDetail(prescription, detailLignes, dispensations);
    }

    /** Prescriptions d'un patient, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<PrescriptionEntity> listerParPatient(UUID patientId) {
        return prescriptionRepository.findByPatientIdOrderByIssuedAtDescIdDesc(patientId);
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private PrescriptionEntity charger(UUID id) {
        return prescriptionRepository.findById(id)
                .orElseThrow(() -> new PrescriptionIntrouvableException(
                        "Prescription introuvable : " + id));
    }

    private BigDecimal cumulPourLigne(UUID ligneId) {
        BigDecimal cumul = BigDecimal.ZERO;
        for (DispensationEntity d : dispensationRepository.findByItemIdOrderByIdAsc(ligneId)) {
            cumul = cumul.add(d.getQuantity());
        }
        return cumul;
    }

    private BigDecimal restantActuel(UUID ligneId) {
        return itemRepository.findById(ligneId)
                .map(ligne -> DispensingRules.restant(ligne.getQuantityPrescribed(),
                        cumulPourLigne(ligneId)))
                .orElse(BigDecimal.ZERO);
    }

    private void audit(UUID acteur, String action, String entite, UUID entiteId,
                       UUID facilityId, String raison, AuditEntryEntity.Result resultat,
                       Map<String, Object> details) {
        auditRecorder.record(acteur, action, entite, entiteId, facilityId, raison, resultat,
                details);
    }
}
