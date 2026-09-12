package bf.publichealth.modules.hub.domain;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Retransmission avec trempe exponentielle et gigue — calcul PUR,
 * testable sans horloge ni aléatoire global : la source d'aléatoire est
 * injectable ({@link DoubleSupplier} rendant une valeur dans [0, 1[).
 *
 * <p>Formule (défauts du protocole) : trempe de base 30 s, croissance
 * ×2^(n-1) où n est le nombre d'échecs déjà subis, gigue uniforme 0-50 %
 * du montant, plafond 1 h sur le délai TOTAL (la gigue ne franchit
 * jamais le plafond — un partenaire lent n'est jamais puni au-delà).</p>
 *
 * <p>Exemples (sans gigue) : 1er échec → 30 s, 2e → 60 s, 3e → 120 s,
 * ... 8e → 64 min plafonnées à 1 h.</p>
 */
public final class BackoffExponentiel {

    /** Trempe de base du protocole : 30 secondes. */
    public static final Duration BASE_DEFAUT = Duration.ofSeconds(30);

    /** Plafond du délai total : 1 heure. */
    public static final Duration PLAFOND_DEFAUT = Duration.ofHours(1);

    /** Gigue maximale : 50 % du montant. */
    public static final double JITTER_MAX_DEFAUT = 0.5;

    private final Duration base;
    private final Duration plafond;
    private final double jitterMax;
    private final DoubleSupplier alea;

    /** Instance du protocole : base 30 s, plafond 1 h, gigue 0-50 %. */
    public BackoffExponentiel() {
        this(BASE_DEFAUT, PLAFOND_DEFAUT, JITTER_MAX_DEFAUT,
                ThreadLocalRandom.current()::nextDouble);
    }

    /** Instance de test : base, plafond, gigue et aléatoire pilotés. */
    public BackoffExponentiel(Duration base, Duration plafond, double jitterMax,
                              DoubleSupplier alea) {
        if (base == null || base.isNegative()) {
            throw new IllegalArgumentException("La trempe de base doit être positive : " + base);
        }
        if (plafond == null || plafond.isNegative() || plafond.compareTo(base) < 0) {
            throw new IllegalArgumentException("Le plafond doit être positif et ≥ base : " + plafond);
        }
        if (jitterMax < 0 || jitterMax > 1) {
            throw new IllegalArgumentException("La gigue maximale doit être dans [0, 1] : " + jitterMax);
        }
        this.base = base;
        this.plafond = plafond;
        this.jitterMax = jitterMax;
        this.alea = alea;
    }

    /**
     * Prochain délai d'attente avant retransmission.
     *
     * @param echecs nombre d'échecs DÉJÀ subis pour ce message (≥ 1) :
     *               après le 1er échec, on attend base ; après le 2e, base × 2…
     * @return délai plafonné, gigue comprise
     */
    public Duration prochainDelai(int echecs) {
        if (echecs < 1) {
            throw new IllegalArgumentException(
                    "Le backoff exige au moins un échec déjà subi : " + echecs);
        }
        // Croissance ×2^(n-1), saturée pour éviter tout débordement long.
        int exposant = Math.min(echecs - 1, 40);
        double montant = base.toMillis() * (1L << exposant);
        double trempe = Math.min(montant, plafond.toMillis());
        double gigue = trempe * alea.getAsDouble() * jitterMax;
        double delai = Math.min(trempe + gigue, plafond.toMillis());
        return Duration.ofMillis(Math.round(delai));
    }

    /** Trempe de base (pour supervision/tests). */
    public Duration base() {
        return base;
    }

    /** Plafond du délai (pour supervision/tests). */
    public Duration plafond() {
        return plafond;
    }
}
