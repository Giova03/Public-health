package bf.publichealth.modules.payments.adapter.persistence;

import bf.publichealth.modules.payments.domain.StatutFacture;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Conversion StatutFacture ↔ code bas-de-casse de la migration V9. */
@Converter(autoApply = false)
public class StatutFactureConverter implements AttributeConverter<StatutFacture, String> {

    @Override
    public String convertToDatabaseColumn(StatutFacture statut) {
        return statut == null ? null : statut.getCode();
    }

    @Override
    public StatutFacture convertToEntityAttribute(String code) {
        return code == null ? null : StatutFacture.depuisCode(code);
    }
}
