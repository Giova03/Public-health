package bf.publichealth.modules.administration.adapter.persistence;

import bf.publichealth.modules.administration.domain.StatutUtilisateur;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Conversion {@link StatutUtilisateur} ↔ code bas-de-casse de la migration V12
 * (patron StatutPrescriptionConverter).
 */
@Converter
public class StatutUtilisateurConverter implements AttributeConverter<StatutUtilisateur, String> {

    @Override
    public String convertToDatabaseColumn(StatutUtilisateur statut) {
        return statut == null ? null : statut.getCode();
    }

    @Override
    public StatutUtilisateur convertToEntityAttribute(String code) {
        return code == null ? null : StatutUtilisateur.depuisCode(code);
    }
}
