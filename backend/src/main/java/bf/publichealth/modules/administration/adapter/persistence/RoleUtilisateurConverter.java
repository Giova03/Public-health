package bf.publichealth.modules.administration.adapter.persistence;

import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversion {@link RoleUtilisateur} ↔ code bas-de-casse de la migration V12
 * (patron StatutPrescriptionConverter).
 */
@Converter
public class RoleUtilisateurConverter implements AttributeConverter<RoleUtilisateur, String> {

    @Override
    public String convertToDatabaseColumn(RoleUtilisateur role) {
        return role == null ? null : role.getCode();
    }

    @Override
    public RoleUtilisateur convertToEntityAttribute(String code) {
        return code == null ? null : RoleUtilisateur.depuisCode(code);
    }
}
