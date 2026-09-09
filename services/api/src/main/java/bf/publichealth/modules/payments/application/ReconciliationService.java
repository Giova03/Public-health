package bf.publichealth.modules.payments.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.payments.adapter.persistence.PaymentEntity;
import bf.publichealth.modules.payments.adapter.persistence.PaymentRepository;
import bf.publichealth.modules.payments.adapter.persistence.PaymentTransitionEntity;
import bf.publichealth.modules.payments.adapter.persistence.PaymentTransitionRepository;
import bf.publichealth.modules.payments.adapter.persistence.ReconciliationDiscrepancyEntity;
import bf.publichealth.modules.payments.adapter.persistence.ReconciliationDiscrepancyRepository;
import bf.publichealth.modules.payments.adapter.persistence.ReconciliationRunEntity;
import bf.publichealth.modules.payments.adapter.persistence.ReconciliationRunRepository;
import bf.publichealth.modules.payments.domain.PaymentState;
import bf.publichealth.modules.payments.domain.ReconciliationVerdict;

/**
 * Réconciliation nocturne — LA source de vérité (ADR-006). Le webhook
 * accélère ; ce run interroge le prestataire par le port
 * {@link FournisseurPaiements} et fait foi.
 *
 * <p>Ordre strict : création du run → examen des paiements non scellés
 * (INITIATED/PENDING/AUTHORIZED/SUCCEEDED, provider_ref connu) →
 * résolution des webhooks orphelins (paiement retrouvé après coup) →
 * rapprochement des factures touchées → clôture du run (compteurs,
 * detail) → audit RECONCILIATION_RUN.</p>
 *
 * <p>Inviolable : forward-only. Une rétrogradation demandée par le
 * prestataire est un ÉCART JOURNALISÉ (reconciliation_discrepancy +
 * audit DENIED), jamais une exception — le run ne crash pas parce que
 * le prestataire se contredit. Les transitions avancées empruntent
 * TOUJOURS le chemin légal (ReconciliationVerdict.cheminAvant) : chaque
 * pas reste dans payment_transition, la preuve comptable.</p>
 */
@Service
public class ReconciliationService {

    /** Rapport d'un run — ce que renvoie POST /api/v1/reconciliation/run. */
    public record RapportRun(UUID id, Instant startedAt, Instant finishedAt, String triggeredBy,
                             Map<String, Object> counts, Map<String, Object> detail) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);

    /** États encore mouvables vers l'encaissement — les états scellés ne sont plus examinés. */
    private static final Set<PaymentState> ETATS_EXAMINABLES = EnumSet.of(
            PaymentState.INITIATED, PaymentState.PENDING, PaymentState.AUTHORIZED,
            PaymentState.SUCCEEDED);

    private final PaymentRepository paymentRepository;
    private final PaymentTransitionRepository transitionRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final InvoiceService invoiceService;
    private final FournisseurPaiements fournisseur;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public ReconciliationService(PaymentRepository paymentRepository,
                                 PaymentTransitionRepository transitionRepository,
                                 ReconciliationRunRepository runRepository,
                                 ReconciliationDiscrepancyRepository discrepancyRepository,
                                 InvoiceService invoiceService, FournisseurPaiements fournisseur,
                                 AuditRecorder auditRecorder, ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.transitionRepository = transitionRepository;
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.invoiceService = invoiceService;
        this.fournisseur = fournisseur;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /**
     * Exécute UN run complet. {@code declencheur} : 'schedule' (job
     * nocturne) ou 'manuel' (POST /api/v1/reconciliation/run).
     */
    @Transactional
    public RapportRun executer(String declencheur) {
        var run = new ReconciliationRunEntity(UuidV7.next(), Instant.now(), declencheur);
        runRepository.save(run);

        Compteurs compteurs = new Compteurs();
        Set<UUID> facturesTouchées = new LinkedHashSet<>();

        examinerPaiements(run, compteurs, facturesTouchées);
        resoudreOrphelins(run, compteurs);
        rapprocherFactures(facturesTouchées, compteurs);

        Map<String, Object> counts = compteurs.compteurs();
        Map<String, Object> detail = compteurs.detail();
        run.terminer(enJson(counts), enJson(detail), Instant.now());
        runRepository.save(run);

        audit(null, "RECONCILIATION_RUN", "reconciliation_run", run.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS, counts);
        LOG.info("Réconciliation terminée run={} declencheur={} comptes={}", run.getId(),
                declencheur, counts);

        return new RapportRun(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                run.getTriggeredBy(), counts, detail);
    }

    // ------------------------------------------------------------------
    // 1. Examen des paiements — verdicts, avancées, écarts
    // ------------------------------------------------------------------

    private void examinerPaiements(ReconciliationRunEntity run, Compteurs compteurs,
                                   Set<UUID> facturesTouchées) {
        List<PaymentEntity> paiements = paymentRepository
                .findByStateInAndProviderRefIsNotNull(ETATS_EXAMINABLES);
        for (PaymentEntity paiement : paiements) {
            compteurs.examines++;
            facturesTouchées.add(paiement.getInvoiceId());

            EtatPrestataire distant = fournisseur.etatDistant(paiement.getProviderRef());
            verifierMontant(run, paiement, distant, compteurs);

            ReconciliationVerdict verdict = ReconciliationVerdict.pour(paiement.getState(),
                    distant.statut());
            switch (verdict.action()) {
                case AVANCER -> avancer(run, paiement, verdict, distant, compteurs);
                case CONFIRMER -> compteurs.confirmes++;
                case IGNORER -> {
                    // Forward-only : le fait accompli reste. Écart + audit DENIED,
                    // PAS d'exception — l'historique est intact.
                    compteurs.ignores++;
                    ecart(run, paiement, distant, verdict.natureEcart(), verdict.explication());
                    audit(null, "RECONCILIATION_DENIED", "payment", paiement.getId(), null,
                            verdict.natureEcart(), AuditEntryEntity.Result.DENIED,
                            Map.of("interne", paiement.getState().name(),
                                    "prestataire", distant.statut().name(),
                                    "runId", run.getId().toString()));
                    LOG.warn("Rétrogradation ignorée payment={} interne={} prestataire={} (run={})",
                            paiement.getId(), paiement.getState(), distant.statut(), run.getId());
                }
                case EXAMINER -> {
                    compteurs.etatsInconnus++;
                    ecart(run, paiement, distant, verdict.natureEcart(), verdict.explication());
                }
            }
        }
    }

    /** Avance le paiement vers la cible par le CHEMIN LÉGAL, un pas à la fois. */
    private void avancer(ReconciliationRunEntity run, PaymentEntity paiement,
                         ReconciliationVerdict verdict, EtatPrestataire distant,
                         Compteurs compteurs) {
        PaymentState initial = paiement.getState();
        List<PaymentState> chemin = ReconciliationVerdict.cheminAvant(initial, verdict.cible());
        for (PaymentState etape : chemin) {
            var pas = paiement.getState();
            paiement.applyTransition(etape);
            paymentRepository.save(paiement);
            transitionRepository.save(new PaymentTransitionEntity(paiement.getId(), pas, etape,
                    "reconciliation:" + run.getId(), distant.idTransactionPrestataire()));
        }

        if (verdict.cible() == PaymentState.FAILED) {
            compteurs.echecs++;
        } else {
            compteurs.avances++;
        }
        audit(null, "PAYMENT_ADVANCED", "payment", paiement.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", initial.name(), "to", paiement.getState().name(),
                        "chemin", chemin.stream().map(Enum::name).toList(),
                        "runId", run.getId().toString(),
                        "declencheur", run.getTriggeredBy()));
        LOG.info("Paiement avancé {} → {} (run={})", initial, paiement.getState(), run.getId());
    }

    /** Écart de montant : le statut fait foi, le montant divergent se trace. */
    private void verifierMontant(ReconciliationRunEntity run, PaymentEntity paiement,
                                 EtatPrestataire distant, Compteurs compteurs) {
        if (distant.montant() != null && paiement.getAmount() != null
                && distant.montant().compareTo(paiement.getAmount()) != 0) {
            compteurs.ecartsMontant++;
            ecart(run, paiement, distant, "MONTANT_DIVERGE",
                    "Montant prestataire %s ≠ montant interne %s".formatted(
                            distant.montant(), paiement.getAmount()));
        }
    }

    // ------------------------------------------------------------------
    // 2. Orphelins — webhooks reçus sans paiement connu, résolus après coup
    // ------------------------------------------------------------------

    private void resoudreOrphelins(ReconciliationRunEntity run, Compteurs compteurs) {
        for (ReconciliationDiscrepancyEntity ecart : discrepancyRepository
                .findByResolvedFalseOrderByCreatedAtAsc()) {
            if (!"WEBHOOK_ORPHELIN".equals(ecart.getKind())) {
                continue;
            }
            var paiement = paymentRepository.findByProviderRef(ecart.getReference());
            if (paiement.isPresent()) {
                // Le paiement est arrivé APRÈS le webhook : l'orphelin est résolu.
                ecart.resoudre(run.getId());
                discrepancyRepository.save(ecart);
                compteurs.orphelinsResolus++;
                audit(null, "ORPHAN_RESOLVED", "payment", paiement.get().getId(), null, null,
                        AuditEntryEntity.Result.SUCCESS,
                        Map.of("reference", ecart.getReference(),
                                "webhookDiscrepancyId", ecart.getId().toString(),
                                "runId", run.getId().toString()));
                LOG.info("Orphelin résolu référence={} payment={} (run={})",
                        ecart.getReference(), paiement.get().getId(), run.getId());
            } else {
                compteurs.orphelinsNonResolus++;
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Rapprochement des factures touchées
    // ------------------------------------------------------------------

    private void rapprocherFactures(Set<UUID> facturesTouchées, Compteurs compteurs) {
        for (UUID factureId : facturesTouchées) {
            var detail = invoiceService.rapprocherPaiements(factureId);
            if (detail != null) {
                compteurs.facturesExaminees++;
            }
        }
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private void ecart(ReconciliationRunEntity run, PaymentEntity paiement, EtatPrestataire distant,
                       String kind, String description) {
        discrepancyRepository.save(new ReconciliationDiscrepancyEntity(UuidV7.next(), run.getId(),
                paiement.getId(), paiement.getProviderRef(), kind, description));
    }

    private String enJson(Map<String, Object> valeur) {
        try {
            return objectMapper.writeValueAsString(valeur);
        } catch (Exception e) {
            throw new IllegalStateException("Sérialisation jsonb impossible", e);
        }
    }

    private void audit(UUID acteur, String action, String entite, UUID entiteId,
                       UUID facilityId, String raison, AuditEntryEntity.Result resultat,
                       Map<String, Object> details) {
        auditRecorder.record(acteur, action, entite, entiteId, facilityId, raison, resultat, details);
    }

    /** Compteurs du run — clés du jsonb counts STRICTEMENT celles du contrat. */
    private static final class Compteurs {
        int examines;
        int confirmes;
        int avances;
        int echecs;
        int ignores;
        int orphelinsResolus;

        // Compléments (detail jsonb) : pas de contrat externe, information interne.
        int etatsInconnus;
        int ecartsMontant;
        int orphelinsNonResolus;
        int facturesExaminees;

        Map<String, Object> compteurs() {
            Map<String, Object> comptes = new LinkedHashMap<>();
            comptes.put("examined", examines);
            comptes.put("confirmed", confirmes);
            comptes.put("advanced", avances);
            comptes.put("failed", echecs);
            comptes.put("ignored", ignores);
            comptes.put("orphans_resolved", orphelinsResolus);
            return comptes;
        }

        Map<String, Object> detail() {
            Map<String, Object> compléments = new LinkedHashMap<>();
            compléments.put("etatsInconnus", etatsInconnus);
            compléments.put("ecartsMontant", ecartsMontant);
            compléments.put("orphelinsNonResolus", orphelinsNonResolus);
            compléments.put("facturesExaminees", facturesExaminees);
            return compléments;
        }
    }
}
