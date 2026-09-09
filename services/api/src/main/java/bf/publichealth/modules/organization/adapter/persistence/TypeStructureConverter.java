package bf.publichealth.modules.organization.adapter.persistence;

import bf.publichealth.modules.organization.domain.TypeStructure;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversion {@link TypeStructure} ↔ code bas-de-casse de la migration V12
 * (patron StatutPrescriptionConverter).
 */
@Converter
public class TypeStructureConverter implements AttributeConverter<TypeStructure, String> {

    @Override
    public String convertToDatabaseColumn(TypeStructure type) {
        return type == null ? null : type.getCode();
    }

    @Override
    public TypeStructure convertToEntityAttribute(String code) {
        return code == null ? null : TypeStructure.depuisCode(code);
    }
}
