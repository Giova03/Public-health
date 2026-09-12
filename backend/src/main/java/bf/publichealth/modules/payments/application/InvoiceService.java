package bf.publichealth.modules.payments.application;

import java.math.BigDecimal;
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
import bf.publichealth.modules.payments.adapter.persistence.InvoiceEntity;
import bf.publichealth.modules.payments.adapter.persistence.InvoiceItemEntity;
import bf.publichealth.modules.payments.adapter.persistence.InvoiceItemRepository;
import bf.publichealth.modules.payments.adapter.persistence.InvoiceRepository;
import bf.publichealth.modules.payments.adapter.persistence.PaymentEntity;
import bf.publichealth.modules.payments.adapter.persistence.PaymentRepository;
import bf.publichealth.modules.payments.domain.IllegalInvoiceTransitionException;
import bf.publichealth.modules.payments.domain.InvoiceIntrouvableException;
import bf.publichealth.modules.payments.domain.InvoiceTotals;
import bf.publichealth.modules.payments.domain.PaymentState;
import bf.publichealth.modules.payments.domain.StatutFacture;

/**
 * Cas d'usage facturation — épique E4.
 *
 * <p>Le total est calculé par le DOMAINE (InvoiceTotals) depuis les
 * lignes : un total soumis par le client serait une falsification en
 * attente. Création idempotente par client_request_id (rejeu = 200, la
 * MÊME facture). Cycle : draft → issued (émission) ; draft|issued →
 * voided (annulation, motif OBLIGATOIRE, TERMINAL) ; issued →
 * partially_paid → paid (rapprochement des paiements encaissés).</p>
 *
 * <p><b>Politique de rapprochement (simple, documentée) :</b> le lien
 * paiement↔facture est établi à l'INITIATION du paiement
 * (payments.payment.invoice_id, contrat V2 — le paiement porte la
 * référence de la facture). Le rapprochement cumule les paiements liés
 * en état SUCCEEDED (montant cohérent : même devise, montant strictement
 * positif — l'argent réellement encaissé fait foi) et fait avancer la
 * facture : issued → partially_paid (0 &lt; cumul &lt; total) → paid
 * (cumul ≥ total). Il ne réaffecte JAMAIS un paiement vers une autre
 * facture : le paiement ne porte pas de patient, tout appariement
 * supplémentaire serait de la divination. Un paiement REFUNDED n'est pas
 * compté (l'argent est reparti) ; une facture draft n'est pas
 * rapprochable (pas encore émise), une facture voided/paid ne bouge plus.</p>
 */
@Service
public class InvoiceService {

    // ------------------------------------------------------------------
    // Entrées / sorties (contrats stables, loi n°5 des modules)
    // ------------------------------------------------------------------

    public record LigneCommande(String libelle, BigDecimal quantite, BigDecimal prixUnitaire) {
    }

    public record CommandeCreation(UUID patientId, UUID encounterId, String currency,
                                   UUID clientRequestId, UUID createdBy,
                                   List<LigneCommande> lignes) {
    }

    /** Facture enrichie : lignes + cumul encaissé (rapprochement). */
    public record InvoiceDetail(InvoiceEntity facture, List<InvoiceItemEntity> lignes,
                                BigDecimal cumulEncaisse) {
    }

    public record CreationFacture(InvoiceDetail detail, boolean rejouee) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final PaymentRepository paymentRepository;
    private final AuditRecorder auditRecorder;

    public InvoiceService(InvoiceRepository invoiceRepository, InvoiceItemRepository itemRepository,
                          PaymentRepository paymentRepository, AuditRecorder auditRecorder) {
        this.invoiceRepository = invoiceRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Création — idempotente, total calculé serveur
    // ------------------------------------------------------------------

    /**
     * Création idempotente : une clé client (client_request_id) = une
     * facture, jamais deux — le rejeu réseau rend la MÊME facture (200),
     * même si le réseau l'a déjà validée.
     */
    @Transactional
    public CreationFacture creer(CommandeCreation commande) {
        if (commande.clientRequestId() != null) {
            var existante = invoiceRepository.findByClientRequestId(commande.clientRequestId());
            if (existante.isPresent()) {
                audit(commande.createdBy(), "INVOICE_CREATED", "invoice",
                        existante.get().getId(), null, "REJEU_CLIENT_REQUEST_ID",
                        AuditEntryEntity.Result.SUCCESS, Map.of("replayed", true));
                return new CreationFacture(trouver(existante.get().getId()), true);
            }
        }

        if (commande.patientId() == null) {
            throw new IllegalArgumentException("La facture porte obligatoirement un patient (uuid nu)");
        }

        // Le domaine fait foi : lignes validées, total calculé serveur.
        List<InvoiceTotals.LigneFacture> lignes = commande.lignes() == null ? List.of()
                : commande.lignes().stream()
                        .map(l -> new InvoiceTotals.LigneFacture(l.libelle(), l.quantite(),
                                l.prixUnitaire()))
                        .toList();
        InvoiceTotals.validerLignes(lignes);
        BigDecimal total = InvoiceTotals.total(lignes);

        InvoiceEntity facture = new InvoiceEntity(UuidV7.next(), commande.patientId(),
                commande.encounterId(), normaliserDevise(commande.currency()), total,
                commande.clientRequestId(), commande.createdBy());
        invoiceRepository.save(facture);

        for (InvoiceTotals.LigneFacture ligne : lignes) {
            itemRepository.save(new InvoiceItemEntity(UuidV7.next(), facture.getId(),
                    ligne.libelle(), ligne.quantite(), ligne.prixUnitaire()));
        }

        audit(commande.createdBy(), "INVOICE_CREATED", "invoice", facture.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("patientId", commande.patientId(), "lignes", lignes.size(),
                        "total", total, "currency", facture.getCurrency()));

        return new CreationFacture(trouver(facture.getId()), false);
    }

    // ------------------------------------------------------------------
    // Émission & annulation — transitions validées par le domaine
    // ------------------------------------------------------------------

    /** Émission : seul DRAFT → ISSUED, horodatée. */
    @Transactional
    public InvoiceDetail emettre(UUID id, UUID acteur) {
        InvoiceEntity facture = charger(id);
        exigerTransition(facture, StatutFacture.ISSUED, acteur);

        facture.emettre();
        invoiceRepository.save(facture);

        audit(acteur, "INVOICE_ISSUED", "invoice", facture.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", StatutFacture.DRAFT.getCode(), "to", StatutFacture.ISSUED.getCode(),
                        "total", facture.getTotal()));
        return trouver(id);
    }

    /** Annulation TERMINALE : draft|issued → voided, motif OBLIGATOIRE. */
    @Transactional
    public InvoiceDetail annuler(UUID id, String motif, UUID acteur) {
        exigerMotif(motif);
        InvoiceEntity facture = charger(id);
        exigerTransition(facture, StatutFacture.VOIDED, acteur);

        var ancien = facture.getStatut();
        facture.annuler(motif);
        invoiceRepository.save(facture);

        audit(acteur, "INVOICE_VOIDED", "invoice", facture.getId(), null, motif,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", ancien.getCode(), "to", StatutFacture.VOIDED.getCode()));
        LOG.info("Facture {} annulée (motif fourni, transition terminale)", facture.getId());
        return trouver(id);
    }

    /** La devise se normalise en majuscules ISO-4217 (XOF par défaut, le franc burkinabè). */
    private static String normaliserDevise(String currency) {
        if (currency == null || currency.isBlank()) {
            return "XOF";
        }
        return currency.trim().toUpperCase();
    }

    /** Le motif d'annulation ne se devine pas : il est OBLIGATOIRE. */
    private void exigerMotif(String motif) {
        if (motif == null || motif.isBlank()) {
            throw new IllegalArgumentException("Le motif d'annulation de la facture est OBLIGATOIRE");
        }
    }

    /** Transition validée par le domaine ; toute tentative illégale est tracée DENIED. */
    private void exigerTransition(InvoiceEntity facture, StatutFacture cible, UUID acteur) {
        try {
            facture.getStatut().exigerTransitionVers(cible);
        } catch (IllegalInvoiceTransitionException e) {
            audit(acteur, "INVOICE_TRANSITION_DENIED", "invoice", facture.getId(), null,
                    "TRANSITION_ILLEGALE", AuditEntryEntity.Result.DENIED,
                    Map.of("from", e.getFrom().getCode(), "to", e.getTo().getCode()));
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Lecture & rapprochement des paiements
    // ------------------------------------------------------------------

    /** Vue complète : facture + lignes + cumul encaissé. */
    @Transactional(readOnly = true)
    public InvoiceDetail trouver(UUID id) {
        InvoiceEntity facture = charger(id);
        return new InvoiceDetail(facture,
                itemRepository.findByInvoiceIdOrderByIdAsc(id),
                cumulEncaisse(facture));
    }

    /** Factures d'un patient, la plus récente d'abord. */
    @Transactional(readOnly = true)
    public List<InvoiceEntity> listerParPatient(UUID patientId) {
        return invoiceRepository.listerParPatient(patientId);
    }

    /**
     * Rapproche les paiements de la facture (politique documentée en
     * tête de classe). Facture inconnue : ignorée en silence (le paiement
     * peut référencer une facture externe au schéma — uuid nu V2).
     */
    @Transactional
    public InvoiceDetail rapprocherPaiements(UUID invoiceId) {
        var existante = invoiceRepository.findById(invoiceId);
        if (existante.isEmpty()) {
            return null;
        }
        InvoiceEntity facture = existante.get();
        if (facture.getStatut() != StatutFacture.ISSUED
                && facture.getStatut() != StatutFacture.PARTIALLY_PAID) {
            // draft : pas encore émise ; voided/paid : terminaux.
            return new InvoiceDetail(facture,
                    itemRepository.findByInvoiceIdOrderByIdAsc(invoiceId),
                    cumulEncaisse(facture));
        }

        BigDecimal cumul = cumulEncaisse(facture);
        if (cumul.signum() <= 0) {
            return new InvoiceDetail(facture,
                    itemRepository.findByInvoiceIdOrderByIdAsc(invoiceId), cumul);
        }

        StatutFacture cible = cumul.compareTo(facture.getTotal()) >= 0
                ? StatutFacture.PAID
                : StatutFacture.PARTIALLY_PAID;
        if (cible == facture.getStatut()) {
            return new InvoiceDetail(facture,
                    itemRepository.findByInvoiceIdOrderByIdAsc(invoiceId), cumul);
        }

        var ancien = facture.getStatut();
        facture.appliquerEncaissement(cible);
        invoiceRepository.save(facture);

        audit(null, "INVOICE_PAYMENT_MATCHED", "invoice", facture.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("from", ancien.getCode(), "to", cible.getCode(),
                        "cumulEncaisse", cumul, "total", facture.getTotal()));
        LOG.info("Facture {} rapprochée : {} (cumul {} / total {})", facture.getId(),
                cible.getCode(), cumul, facture.getTotal());

        return new InvoiceDetail(facture,
                itemRepository.findByInvoiceIdOrderByIdAsc(invoiceId), cumul);
    }

    /**
     * Cumul encaissé : paiements SUCCEEDED liés à la facture, même
     * devise, montant positif — l'argent réellement en caisse.
     */
    private BigDecimal cumulEncaisse(InvoiceEntity facture) {
        BigDecimal cumul = BigDecimal.ZERO;
        for (PaymentEntity paiement : paymentRepository.findByInvoiceId(facture.getId())) {
            if (paiement.getState() == PaymentState.SUCCEEDED
                    && facture.getCurrency().equals(paiement.getCurrency())
                    && paiement.getAmount().signum() > 0) {
                cumul = cumul.add(paiement.getAmount());
            }
        }
        return cumul.setScale(InvoiceTotals.ECHELLE);
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private InvoiceEntity charger(UUID id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new InvoiceIntrouvableException(id));
    }

    private void audit(UUID acteur, String action, String entite, UUID entiteId,
                       UUID facilityId, String raison, AuditEntryEntity.Result resultat,
                       Map<String, Object> details) {
        auditRecorder.record(acteur, action, entite, entiteId, facilityId, raison, resultat, details);
    }
}
