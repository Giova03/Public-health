package bf.publichealth.modules.hub.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.hub.domain.BackoffExponentiel;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.PolitiqueRetry;
import bf.publichealth.modules.hub.domain.ReponseTransport;

/**
 * Livreur d'un message HUB — les étapes transactionnelles courtes
 * (REQUIRES_NEW, appelées depuis l'ordonnanceur hors transaction) :
 * le départ ({@code sent}) et le traitement du verdict (acquitté /
 * échec transitoire / lettre morte + filigrane + journal + audit).
 *
 * <p>Le transport (I/O réseau) est appelé PAR l'ordonnanceur, ENTRE les
 * deux transactions : aucune transaction ne reste ouverte pendant un
 * appel réseau.</p>
 */
@Service
public class LivreurMessage {

    private static final Logger LOG = LoggerFactory.getLogger(LivreurMessage.class);

    private final EntrepotHub entrepot;
    private final PolitiqueRetry politique;
    private final BackoffExponentiel backoff;
    private final AuditRecorder auditRecorder;

    public LivreurMessage(EntrepotHub entrepot, PolitiqueRetry politique,
                          BackoffExponentiel backoff, AuditRecorder auditRecorder) {
        this.entrepot = entrepot;
        this.politique = politique;
        this.backoff = backoff;
        this.auditRecorder = auditRecorder;
    }

    /** Départ du message : {@code sent}, échéance de reprise posée (si la réponse ne vient jamais). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void partir(MessageHub message, Instant maintenant, Instant repriseA) {
        entrepot.marquerEnvoye(message.id(), maintenant, repriseA);
    }

    /** Éventualité d'un acquittement accompagné d'un trou de séquence. */
    public record ResultatVerdict(String verdict, Optional<TrouSequence> trou) {
    }

    /** Un trou détecté : la destination a acquitté au-delà d'une séquence jamais confirmée. */
    public record TrouSequence(String destination, long sequence, long filigraneAvant, long ecart) {
    }

    /**
     * Traite la réponse du transport : applique le verdict, avance le
     * filigrane, journalise la tentative, détecte les trous de séquence
     * (journalisés en ERROR) et pose l'audit DENIED de chaque passage en
     * lettre morte (REQUIRES_NEW — l'audit survit même à un rollback).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResultatVerdict traiterVerdict(MessageHub message, ReponseTransport reponse,
                                          Instant maintenant) {
        int tentativesEffectuees = message.attempts() + 1;
        PolitiqueRetry.Verdict verdict = politique.verdict(reponse, tentativesEffectuees);

        switch (verdict) {
            case ACQUITTER -> {
                entrepot.journaliser(message.id(), tentativesEffectuees, "ACK",
                        reponse.detail());
                entrepot.marquerAcquitte(message.id(), maintenant);

                // Filigrane : relecture sous verrou, puis GREATEST — monotone.
                Destination destination = entrepot.verrouillerDestination(message.destinationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Destination disparue : " + message.destinationId()));
                long filigraneAvant = destination.watermark();
                long sequenceConfirmee = Math.max(message.sequence(),
                        reponse.watermark() == null ? message.sequence() : reponse.watermark());
                entrepot.avancerWatermark(destination.id(), sequenceConfirmee);

                // Le protocole l'exige : un acquittement au-delà de
                // filigrane+1 révèle un trou (séquence jamais confirmée).
                if (sequenceConfirmee > filigraneAvant + 1) {
                    TrouSequence trou = new TrouSequence(destination.code(), sequenceConfirmee,
                            filigraneAvant, sequenceConfirmee - filigraneAvant - 1);
                    LOG.error("TROU DE SÉQUENCE HUB : destination {} a acquitté la séquence {} "
                                    + "alors que le filigrane était {} — {} séquence(s) jamais confirmée(s)",
                            destination.code(), sequenceConfirmee, filigraneAvant, trou.ecart());
                    return new ResultatVerdict("ACQUITTER", Optional.of(trou));
                }
                return new ResultatVerdict("ACQUITTER", Optional.empty());
            }
            case RETENTER -> {
                // Échec transitoire : le message retourne en file, tentative
                // comptée, prochaine échéance = maintenant + backoff.
                Instant prochainEssai = maintenant.plus(backoff.prochainDelai(tentativesEffectuees));
                entrepot.journaliser(message.id(), tentativesEffectuees, "ECHEC_TRANSITOIRE",
                        reponse.detail());
                entrepot.marquerEchec(message.id(), tentativesEffectuees, prochainEssai,
                        reponse.detail());
                return new ResultatVerdict("RETENTER", Optional.empty());
            }
            case LETTER_MORTE -> {
                String raison = reponse.definitif() ? "REJET_DEFINITIF" : "TENTATIVES_EPUISEES";
                String detail = reponse.detail();
                entrepot.journaliser(message.id(), tentativesEffectuees,
                        reponse.definitif() ? "REJET_DEFINITIF" : "DLQ_TENTATIVES", detail);
                entrepot.marquerMort(message.id(), tentativesEffectuees, maintenant, detail);
                auditMort(message, tentativesEffectuees, raison, detail);
                return new ResultatVerdict("LETTER_MORTE", Optional.empty());
            }
            default -> throw new IllegalStateException("Verdict inconnu : " + verdict);
        }
    }

    /** Audit DENIED de chaque passage en lettre morte (patron payments). */
    private void auditMort(MessageHub message, int tentatives, String raison, String detail) {
        auditRecorder.record(null, "HUB_DLQ", "hub_message", message.id(), null, raison,
                AuditEntryEntity.Result.DENIED,
                Map.of("sequence", message.sequence(),
                        "attempts", tentatives,
                        "detail", detail == null ? "" : detail));
    }
}
