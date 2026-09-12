package bf.publichealth.modules.appointment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.appointment.adapter.persistence.AppointmentJdbc;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.consultation.adapter.persistence.ConsultationJdbc;

/**
 * Service rendez-vous (V14, P1) — la machine à états du RDV.
 *
 * <p>Règles (audit N2-Q10, I10) :</p>
 * <ul>
 *   <li>transitions légales : demande→confirme→honore ;
 *       demande/confirme→annule ; confirme→absent ;</li>
 *   <li>motif d'annulation OBLIGATOIRE ;</li>
 *   <li>un RDV PASSÉ est IMMUABLE : plus aucune transition (l'historique
 *       fait foi) — sauf honorer/absent consignés PAR UN AGENT après
 *       le créneau (l'enregistrement du résultat, pas une modification) ;</li>
 *   <li>patient : MAX 1 rendez-vous non annulé par jour (quota, N3-Q13) ;</li>
 *   <li>patient décédé (I15) et idempotence offline clientRequestId.</li>
 * </ul>
 */
@Service
public class AppointmentService {

    private final AppointmentJdbc rendezvous;
    private final ConsultationJdbc consultations;
    private final AuditRecorder audit;

    public AppointmentService(AppointmentJdbc rendezvous, ConsultationJdbc consultations,
                              AuditRecorder audit) {
        this.rendezvous = rendezvous;
        this.consultations = consultations;
        this.audit = audit;
    }

    public static class RdvRefuseException extends RuntimeException {
        public RdvRefuseException(String message) {
            super(message);
        }
    }

    public record CommandeCreation(UUID patientId, UUID structureId, UUID practitionerId,
                                   String type, Instant creneau, String motif,
                                   String demandePar, UUID clientRequestId) {
    }

    @Transactional
    public AppointmentJdbc.RendezVous creer(CommandeCreation commande, UUID acteur) {
        String etat = consultations.etatPatient(commande.patientId());
        if ("introuvable".equals(etat)) {
            throw new RdvRefuseException("Patient introuvable : " + commande.patientId());
        }
        if ("decede".equals(etat)) {
            throw new RdvRefuseException("Dossier scellé : décès déclaré — aucun rendez-vous");
        }
        if (commande.creneau() == null || commande.creneau().isBefore(Instant.now())) {
            throw new RdvRefuseException("Le créneau doit être dans le FUTUR");
        }

        // Quota patient : 1 rendez-vous non annulé par jour.
        if ("patient".equals(commande.demandePar())
                && rendezvous.compterNonAnnulesDuJour(commande.patientId(), commande.creneau()) >= 1) {
            throw new RdvRefuseException(
                    "Quota atteint : 1 rendez-vous par jour maximum (annulez le précédent)");
        }

        // Idempotence offline.
        if (commande.clientRequestId() != null) {
            Optional<UUID> existant = rendezvous.parClientRequestId(commande.clientRequestId());
            if (existant.isPresent()) {
                return rendezvous.trouver(existant.get()).orElseThrow();
            }
        }

        UUID id = UUID.randomUUID();
        rendezvous.inserer(id, commande.patientId(), commande.structureId(), commande.practitionerId(),
                commande.type() == null ? "general" : commande.type(), commande.creneau(),
                commande.motif(), commande.demandePar(), commande.clientRequestId(), acteur);
        audit.record(acteur, "RDV_CREATED", "rendez_vous", id, commande.structureId(),
                commande.motif(), AuditEntryEntity.Result.SUCCESS,
                java.util.Map.of("patientId", commande.patientId().toString(),
                        "creneau", commande.creneau().toString()));
        return rendezvous.trouver(id).orElseThrow();
    }

    /** Confirmer (agent) : demande→confirme. */
    @Transactional
    public AppointmentJdbc.RendezVous confirmer(UUID id, UUID acteur) {
        AppointmentJdbc.RendezVous rdv = charger(id);
        exigerTransitionLegale(rdv, "confirme", false);
        rendezvous.majEtat(id, "confirme", null, acteur);
        audit.record(acteur, "RDV_TRANSITION", "rendez_vous", id, rdv.structureId(),
                "demande→confirme", AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    /** Consigner l'honoration (agent, au/après le créneau). */
    @Transactional
    public AppointmentJdbc.RendezVous honorer(UUID id, UUID acteur) {
        AppointmentJdbc.RendezVous rdv = charger(id);
        exigerTransitionLegale(rdv, "honore", true);
        rendezvous.majEtat(id, "honore", null, acteur);
        audit.record(acteur, "RDV_TRANSITION", "rendez_vous", id, rdv.structureId(),
                "→honore", AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    /** Consigner l'absence (agent, après le créneau). */
    @Transactional
    public AppointmentJdbc.RendezVous absent(UUID id, UUID acteur) {
        AppointmentJdbc.RendezVous rdv = charger(id);
        exigerTransitionLegale(rdv, "absent", true);
        rendezvous.majEtat(id, "absent", null, acteur);
        audit.record(acteur, "RDV_TRANSITION", "rendez_vous", id, rdv.structureId(),
                "→absent", AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    /** Annuler (patient sur SON rdv futur, ou agent) — motif OBLIGATOIRE. */
    @Transactional
    public AppointmentJdbc.RendezVous annuler(UUID id, String motif, UUID acteur) {
        if (motif == null || motif.isBlank()) {
            throw new RdvRefuseException("Le motif d'annulation est OBLIGATOIRE");
        }
        AppointmentJdbc.RendezVous rdv = charger(id);
        exigerTransitionLegale(rdv, "annule", false);
        rendezvous.majEtat(id, "annule", motif.trim(), acteur);
        audit.record(acteur, "RDV_CANCELLED", "rendez_vous", id, rdv.structureId(),
                motif.trim(), AuditEntryEntity.Result.SUCCESS, null);
        return charger(id);
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AppointmentJdbc.RendezVous> lister(UUID patientId, UUID structureId, String statut) {
        return rendezvous.lister(patientId, structureId, statut);
    }

    @Transactional(readOnly = true)
    public AppointmentJdbc.RendezVous trouver(UUID id) {
        return charger(id);
    }

    private AppointmentJdbc.RendezVous charger(UUID id) {
        return rendezvous.trouver(id)
                .orElseThrow(() -> new RdvRefuseException("Rendez-vous introuvable : " + id));
    }

    /**
     * Légalité de la transition. cible honore/absent : autorisées seulement
     * APRÈS le créneau (consignation du résultat) ; toutes les autres :
     * uniquement AVANT (un RDV passé est immuable — I10).
     */
    private static void exigerTransitionLegale(AppointmentJdbc.RendezVous rdv, String cible,
                                               boolean apresCreneau) {
        boolean legal = switch (rdv.statut()) {
            case "demande" -> "confirme".equals(cible) || "annule".equals(cible)
                    || ("honore".equals(cible) && apresCreneau);
            case "confirme" -> "annule".equals(cible) || "honore".equals(cible)
                    || "absent".equals(cible);
            default -> false; // honore/annule/absent : TERMINAUX
        };
        if (!legal) {
            throw new RdvRefuseException(
                    "Transition illégale : " + rdv.statut() + " → " + cible + " est interdit");
        }
        if (!apresCreneau && rdv.creneau().isBefore(Instant.now())) {
            throw new RdvRefuseException(
                    "Rendez-vous passé : immuable (l'historique fait foi)");
        }
        if (apresCreneau && rdv.creneau().isAfter(Instant.now())
                && !"annule".equals(cible)) {
            throw new RdvRefuseException(
                    "On ne peut consigner " + cible + " qu'au créneau ou après");
        }
    }
}
