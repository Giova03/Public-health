package bf.publichealth.modules.prescription.adapter.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import bf.publichealth.modules.prescription.domain.StatutPrescription;

/**
 * Convertit le statut de prescription entre le domaine (ACTIVE) et la
 * colonne SQL V8 (valeur bas-de-casse du CHECK : 'active', 'cancelled',
 * 'entered-in-error').
 */
@Converter
public class StatutPrescriptionConverter
        implements AttributeConverter<StatutPrescription, String> {

    @Override
    public String convertToDatabaseColumn(StatutPrescription statut) {
        return statut == null ? null : statut.getCode();
    }

    @Override
    public StatutPrescription convertToEntityAttribute(String code) {
        return code == null ? null : StatutPrescription.depuisCode(code);
    }
}
