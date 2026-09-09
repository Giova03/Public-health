package bf.publichealth.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UUID v7 — la fondation du sans-conflit offline (épique E2) :
 * générés côté client, ordonnés dans le temps, uniques.
 */
class UuidV7Test {

    @Test
    @DisplayName("Le numéro de version est 7 (RFC 9562)")
    void versionIs7() {
        assertThat(UuidV7.next().version()).isEqualTo(7);
        assertThat(UuidV7.next().variant()).isEqualTo(2); // variante RFC 4122
    }

    @Test
    @DisplayName("Unicité sur un lot massif")
    void unique() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(UuidV7.next());
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    @DisplayName("Ordonnés dans le temps : l'horodatage ne diminue jamais")
    void monotonicTimestamp() throws InterruptedException {
        long previous = extractUnixMs(UuidV7.next());
        for (int i = 0; i < 100; i++) {
            long current = extractUnixMs(UuidV7.next());
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
            if (i % 25 == 0) {
                Thread.sleep(2); // franchit au moins quelques millisecondes
            }
        }
    }

    private static long extractUnixMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
