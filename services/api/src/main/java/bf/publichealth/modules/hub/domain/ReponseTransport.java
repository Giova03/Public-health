package bf.publichealth.modules.hub.domain;

import java.util.OptionalLong;

/**
 * Réponse d'un transport HUB après envoi d'une enveloppe.
 *
 * @param acquitte  la destination accuse réception (l'enveloppe est
 *                  définitivement reçue — le filigrane peut avancer)
 * @param definitif échec DÉFINITIF (rejet durable : la retransmission
 *                  ne servira plus rien — lettre morte immédiate)
 * @param watermark filigrane confirmé par la destination, si fourni
 *                  (monotone côté plateforme : seul GREATEST est appliqué)
 * @param detail    diagnostic humain (journalisé dans delivery_log,
 *                  jamais le secret)
 */
public record ReponseTransport(boolean acquitte, boolean definitif, Long watermark,
                               String detail) {

    /** Réponse d'acquittement avec filigrane confirmé. */
    public static ReponseTransport acquitte(Long watermark, String detail) {
        return new ReponseTransport(true, false, watermark, detail);
    }

    /** Échec transitoire : on retransmettra avec trempe et gigue. */
    public static ReponseTransport echecTransitoire(String detail) {
        return new ReponseTransport(false, false, null, detail);
    }

    /** Rejet définitif : lettre morte immédiate, aucune retransmission. */
    public static ReponseTransport rejetDefinitif(String detail) {
        return new ReponseTransport(false, true, null, detail);
    }

    /** Filigrane confirmé, ou vide si la destination n'en a pas rendu. */
    public OptionalLong filigrane() {
        return watermark == null ? OptionalLong.empty() : OptionalLong.of(watermark);
    }
}
