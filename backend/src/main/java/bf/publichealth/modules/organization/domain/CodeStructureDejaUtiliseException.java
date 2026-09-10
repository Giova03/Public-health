package bf.publichealth.modules.organization.domain;

/**
 * Mnémonique officielle déjà attribuée — 409 au niveau HTTP.
 *
 * <p>Le code (ex. {@code CSPS-OUA-014}) identifie la structure dans
 * tout l'écosystème (paiements, prescriptions, FHIR) : il est UNIQUE.
 * La garde applicative est insensible à la casse — plus stricte que
 * l'index UNIQUE SQL exact (course improbable documentée : deux
 * écritures simultanées ne différant que par la casse passeraient la
 * garde SQL ; l'annuaire est écrit par le seul back-office admin).</p>
 */
public class CodeStructureDejaUtiliseException extends RuntimeException {

    private final String code;

    public CodeStructureDejaUtiliseException(String code) {
        super("Une structure sanitaire existe déjà avec le code " + code
                + " — la mnémonique officielle est unique");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
