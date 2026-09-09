package bf.publichealth.modules.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.payments.domain.InvoiceTotals;

/**
 * Unitaires du calcul de facture — le total est un FAIT SERVEUR : lignes
 * validées, arrondi commercial 2 décimales, jamais un total client.
 */
class InvoiceTotalsTest {

    private static InvoiceTotals.LigneFacture ligne(String libelle, String quantite, String prix) {
        return new InvoiceTotals.LigneFacture(libelle, new BigDecimal(quantite),
                new BigDecimal(prix));
    }

    @Test
    @DisplayName("Total simple : somme des lignes, en 2 décimales")
    void totalSimple() {
        BigDecimal total = InvoiceTotals.total(List.of(
                ligne("Consultation générale", "1", "2500"),
                ligne("Pansement", "2", "1250.50")));
        assertThat(total).isEqualByComparingTo("5001.00");
        assertThat(total.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Arrondi commercial HALF_UP par ligne : 3 × 0.335 = 1.01")
    void arrondiParLigne() {
        assertThat(InvoiceTotals.total(List.of(ligne("Acte", "3", "0.335"))))
                .isEqualByComparingTo("1.01");
        // 0.335 × 3 = 1.005 → la LIGNE vaut 1.005 arrondie à 1.01 (pas le total brut 1.005).
    }

    @Test
    @DisplayName("Prix unitaire nul autorisé (ligne offerte), total reste >= 0")
    void prixFNulAutorise() {
        assertThat(InvoiceTotals.total(List.of(
                ligne("Consultation", "1", "3000"),
                ligne("Carnet offert", "1", "0"))))
                .isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("Montant de ligne : quantité × prix, arrondi 2 décimales")
    void montantDeLigne() {
        assertThat(InvoiceTotals.montantLigne(ligne("Test", "1.5", "9.99")))
                .isEqualByComparingTo("14.99"); // 14.985 → HALF_UP → 14.99
    }

    @Test
    @DisplayName("Une facture sans ligne est refusée")
    void sansLigne() {
        assertThatThrownBy(() -> InvoiceTotals.total(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("au moins une ligne");
        assertThatThrownBy(() -> InvoiceTotals.total(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Ligne incomplète : libellé, quantité ou prix manquant → refus")
    void ligneInvalide() {
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(
                new InvoiceTotals.LigneFacture(null, BigDecimal.ONE, BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(
                new InvoiceTotals.LigneFacture("  ", BigDecimal.ONE, BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(
                new InvoiceTotals.LigneFacture("Acte", null, BigDecimal.TEN))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(
                new InvoiceTotals.LigneFacture("Acte", BigDecimal.ONE, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Quantité strictement positive, prix non négatif — sinon refus")
    void quantiteEtPrixBornes() {
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(ligne("Acte", "0", "100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictement positive");
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(ligne("Acte", "-1", "100"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceTotals.total(List.of(ligne("Acte", "1", "-0.01"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positif ou nul");
    }

    @Test
    @DisplayName("numeric(12,2) : 12 chiffres entiers maximum, sinon refus du domaine")
    void debordementNumerique() {
        assertThatThrownBy(() -> InvoiceTotals.total(
                List.of(ligne("Acte", "123456789012.00", "1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 chiffres");
        assertThatThrownBy(() -> InvoiceTotals.total(
                List.of(ligne("Acte", "1", "123456789012.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
