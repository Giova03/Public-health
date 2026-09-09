package bf.publichealth.modules.payments.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Statut d'une facture — mutable par transitions, {@code voided} TERMINAL.
 *
 * <pre>
 * draft ──▶ issued ──▶ partially_paid ──▶ paid
 *   │          │
 *   └──▶ voided└──▶ voided               (paid : terminal)
 * </pre>
 *
 * <p>{@code voided} et {@code paid} sont terminaux : aucun retour en
 * arrière n'existe (la garde SQL de la migration V9 fait respecter cette
 * loi, même en UPDATE direct). L'annulation n'est possible que depuis
 * {@code draft} ou {@code issued} — une facture partiellement encaissée
 * ne s'annule pas, elle se régule (remboursement/avoir, hors périmètre).</p>
 *
 * <p>Le code bas-de-casse correspond aux valeurs CHECK de V9 ; la
 * conversion JPA vit dans l'adaptateur de persistance.</p>
 */
public enum StatutFacture {

    DRAFT("draft"),
    ISSUED("issued"),
    PARTIALLY_PAID("partially_paid"),
    PAID("paid"),
    VOIDED("voided");

    private static final Map<StatutFacture, Set<StatutFacture>> TRANSITIONS_LEGALES = Map.of(
            DRAFT, EnumSet.of(ISSUED, VOIDED),
            ISSUED, EnumSet.of(PARTIALLY_PAID, PAID, VOIDED),
            PARTIALLY_PAID, EnumSet.of(PAID),
            PAID, EnumSet.noneOf(StatutFacture.class),
            VOIDED, EnumSet.noneOf(StatutFacture.class));

    private final String code;

    StatutFacture(String code) {
        this.code = code;
    }

    /** Valeur persistée en base (migration V9). */
    public String getCode() {
        return code;
    }

    /** Terminal = plus aucune transition légale (paid encaissé, voided annulé). */
    public boolean estTerminal() {
        return this == PAID || this == VOIDED;
    }

    /** L'annulation n'existe que pour un brouillon ou une facture émise. */
    public boolean estAnnulable() {
        return this == DRAFT || this == ISSUED;
    }

    public boolean peutAllerA(StatutFacture cible) {
        return TRANSITIONS_LEGALES.get(this).contains(cible);
    }

    /**
     * Vérifie la légalité de la transition, sinon lève
     * {@link IllegalInvoiceTransitionException}. Toute tentative illégale
     * est journalisée par l'appelant (audit six dimensions, DENIED).
     */
    public void exigerTransitionVers(StatutFacture cible) {
        if (!peutAllerA(cible)) {
            throw new IllegalInvoiceTransitionException(this, cible);
        }
    }

    /** Résout le statut depuis la valeur persistée (base / SQL natif). */
    public static StatutFacture depuisCode(String code) {
        for (StatutFacture statut : values()) {
            if (statut.code.equals(code)) {
                return statut;
            }
        }
        throw new IllegalArgumentException("Statut de facture inconnu : " + code);
    }
}
