package bf.publichealth.modules.administration.domain;

import java.util.UUID;

/**
 * Compte Supabase déjà lié — 409 au niveau HTTP.
 *
 * <p>La liaison {@code supabase_user_id} est DÉFINITIVE (UNIQUE en
 * base) : un compte Supabase Auth appartient à un et un seul
 * utilisateur du back-office. La réinviter sur un autre utilisateur,
 * ou re-lier un compte déjà lié, est refusé avec l'identité du
 * porteur actuel — l'admin corrige sans deviner.</p>
 */
public class CompteDejaLierException extends RuntimeException {

    private final UUID supabaseUserId;
    private final UUID utilisateurPorteur;

    public CompteDejaLierException(UUID supabaseUserId, UUID utilisateurPorteur, String emailPorteur) {
        super("Le compte Supabase " + supabaseUserId + " est déjà lié à l'utilisateur "
                + utilisateurPorteur + (emailPorteur == null ? "" : " (" + emailPorteur + ")")
                + " — la liaison est définitive");
        this.supabaseUserId = supabaseUserId;
        this.utilisateurPorteur = utilisateurPorteur;
    }

    public UUID getSupabaseUserId() {
        return supabaseUserId;
    }

    public UUID getUtilisateurPorteur() {
        return utilisateurPorteur;
    }
}
