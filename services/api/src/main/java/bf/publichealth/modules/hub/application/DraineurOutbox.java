package bf.publichealth.modules.hub.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.hub.domain.Destination;
import bf.publichealth.modules.hub.domain.EnveloppeHub;
import bf.publichealth.modules.hub.domain.EvenementOutbox;
import bf.publichealth.modules.hub.domain.MessageHub;
import bf.publichealth.modules.hub.domain.SignatureHmac;

/**
 * Draineur du transactional outbox {@code sync.outbox} — le cœur du
 * connecteur (le hub est la porte de migration Kafka annoncée).
 *
 * <p>Pour chaque lot d'événements non publiés et chaque destination
 * active : attribution de la séquence monotone (verrou applicatif sur
 * hub.destination + contrainte UNIQUE (destination_id, sequence) en
 * dernière défense), construction de l'enveloppe canonique, signature
 * HMAC-SHA256, enregistrement du message queued — puis marquage
 * {@code published = true} dans la MÊME transaction locale : soit
 * l'événement part en file HUB et est marqué, soit rien ne bouge.</p>
 */
@Service
public class DraineurOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(DraineurOutbox.class);

    private final EntrepotHub entrepot;
    private final GestionnaireSecrets secrets;

    public DraineurOutbox(EntrepotHub entrepot, GestionnaireSecrets secrets) {
        this.entrepot = entrepot;
        this.secrets = secrets;
    }

    /** Résultat du passage de drainage (création seule — l'envoi suit). */
    public record ResultatDrain(int messagesCrees, List<UUID> evenementsPublies) {
    }

    /**
     * Drainage d'un lot : lit les événements non publiés et crée les
     * messages queued par destination active, transaction unique.
     */
    @Transactional
    public ResultatDrain drainer(int lotMax) {
        List<EvenementOutbox> evenements = entrepot.evenementsNonPublies(lotMax);
        if (evenements.isEmpty()) {
            return new ResultatDrain(0, List.of());
        }

        List<Destination> actives = entrepot.destinationsActives();
        if (actives.isEmpty()) {
            // Rien n'est perdu : published n'est marqué QUE si au moins une
            // destination a pris l'événement en file (voir ci-dessous).
            LOG.warn("Outbox non vide ({} événements) mais AUCUNE destination active "
                    + "— le drainage attendra une destination", evenements.size());
            return new ResultatDrain(0, List.of());
        }

        Instant emisA = Instant.now();
        int crees = 0;
        for (Destination destination : actives) {
            // Verrou applicatif : sérialise l'attribution des séquences par
            // destination (destinations parcourues en ordre d'id — pas de
            // verrous croisés).
            Destination verrouillee = entrepot.verrouillerDestination(destination.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Destination active disparue pendant le drainage : " + destination.code()));

            // La séquence repart du maximum des messages ET du filigrane :
            // jamais de réutilisation, même après purge locale.
            long sequence = Math.max(entrepot.sequenceMax(verrouillee.id()),
                    verrouillee.watermark());
            for (EvenementOutbox evenement : evenements) {
                sequence++;
                crees += enregistrerMessage(verrouillee, evenement, sequence, emisA);
            }
        }

        List<UUID> publies = evenements.stream().map(EvenementOutbox::eventId).toList();
        entrepot.marquerPublies(publies);
        LOG.info("Drainage : {} événements outbox → {} messages HUB vers {} destination(s)",
                evenements.size(), crees, actives.size());
        return new ResultatDrain(crees, publies);
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private int enregistrerMessage(Destination destination, EvenementOutbox evenement,
                                   long sequence, Instant emisA) {
        EnveloppeHub enveloppe = EnveloppeHub.de(evenement, destination.code(), sequence, emisA);
        String secret = secrets.secretPour(destination.code());
        String signature = SignatureHmac.signer(enveloppe.octetsCanoniques(), secret);
        MessageHub message = new MessageHub(UuidV7.next(), evenement.eventId(), destination.id(),
                sequence, enveloppe.jsonCanonique(), signature, "queued", 0, null,
                null, null, null, Instant.now());
        entrepot.enregistrerMessage(message);
        return 1;
    }
}
