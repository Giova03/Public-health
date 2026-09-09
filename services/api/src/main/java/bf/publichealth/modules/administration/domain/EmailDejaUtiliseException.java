package bf.publichealth.modules.administration.domain;

/**
 * Email déjà utilisé par un utilisateur — 409 au niveau HTTP.
 *
 * <p>L'idempotence d'invitation est par EMAIL : ré-inviter une adresse
 * connue n'est jamais un rejeu silencieux — le 409 est clair et porte
 * le STATUT du compte existant (notamment suspendu : ré-inviter un
 * suspendu ne le réinvite pas par erreur, il faut le réactiver, à des
 * fins délibérées, via /reactivate).</p>
 */
public class EmailDejaUtiliseException extends RuntimeException {

    private final String email;
    private final String statutExistant;

    public EmailDejaUtiliseException(String email, String statutExistant) {
        super("Un utilisateur existe déjà avec l'email " + email + " (statut " + statutExistant
                + ") — " + ("suspendu".equals(statutExistant)
                        ? "compte suspendu : le réactiver via /reactivate, pas le réinviter"
                        : "l'invitation est déjà en cours ou le compte déjà créé"));
        this.email = email;
        this.statutExistant = statutExistant;
    }

    public String getEmail() {
        return email;
    }

    public String getStatutExistant() {
        return statutExistant;
    }
}
