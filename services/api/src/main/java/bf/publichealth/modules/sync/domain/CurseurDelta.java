package bf.publichealth.modules.sync.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Curseur opaque du miroir descendant (delta) — clé de pagination keyset.
 *
 * <p>Format sérialisé : {@code v1:<microsecondes-epoch>:<uuid-departage>}.
 * Le client ne l'interprète JAMAIS : il le renvoie tel quel au prochain
 * appel. Le départage par identifiant garantit qu'aucune ligne n'est
 * perdue ni relue lorsque deux patients partagent exactement le même
 * {@code updated_at} (précision microseconde de PostgreSQL).</p>
 *
 * <p>Sémantique : la requête delta remonte les patients dont la clé
 * {@code (updated_at, id)} est strictement supérieure à la clé du
 * curseur — chaque ligne est délivrée exactement une fois.</p>
 */
public record CurseurDelta(Instant instant, UUID identifiant) {

    /** Départ : tout l'historique du miroir. */
    public static final CurseurDelta ORIGINE = new CurseurDelta(Instant.EPOCH,
            UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private static final String VERSION_FORMAT = "v1";

    /**
     * Analyse un curseur reçu. Absent ou blanc = départ ; malformé =
     * {@link CurseurInvalideException} (le contrôleur répond 400).
     */
    public static CurseurDelta parse(String brut) {
        if (brut == null || brut.isBlank()) {
            return ORIGINE;
        }
        String[] morceaux = brut.strip().split(":", 3);
        if (morceaux.length != 3 || !VERSION_FORMAT.equals(morceaux[0])) {
            throw new CurseurInvalideException(brut);
        }
        try {
            long micros = Long.parseLong(morceaux[1]);
            UUID identifiant = UUID.fromString(morceaux[2]);
            return new CurseurDelta(
                    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                            Math.floorMod(micros, 1_000_000L) * 1_000L),
                    identifiant);
        } catch (IllegalArgumentException e) { // englobe NumberFormatException
            throw new CurseurInvalideException(brut);
        }
    }

    /** Sérialisation opaque renvoyée au client. */
    public String encoder() {
        long micros = instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
        return VERSION_FORMAT + ":" + micros + ":" + identifiant;
    }
}
