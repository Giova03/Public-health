package bf.publichealth.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Générateur UUID version 7 (RFC 9562) : ordonné dans le temps, indexable.
 *
 * <p>Utilisé pour toute entité créée potentiellement HORS LIGNE :
 * l'identifiant est généré côté client, sans aller-retour serveur,
 * ce qui élimine par conception les conflits de clés à la synchronisation
 * (épique E2). Côté serveur, il garantit des index B-tree compacts.</p>
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        long unixTsMs = System.currentTimeMillis();
        // 48 bits d'horodatage | version 7 (0111) | 12 bits aléatoires
        long msb = (unixTsMs & 0xFFFFFFFFFFFFL) << 16 | 0x7000L | RANDOM.nextInt(0x1000);
        // variante RFC 4122/9562 (bits 62-63 = 10) | 62 bits aléatoires
        long lsb = 0x8000000000000000L | (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
