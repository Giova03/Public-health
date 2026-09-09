package bf.publichealth.modules.sync.domain;

/**
 * Curseur de delta illisible — 400 côté web (le client doit renvoyer le
 * curseur tel que le serveur l'a émis).
 */
public class CurseurInvalideException extends RuntimeException {

    public CurseurInvalideException(String curseur) {
        super("Curseur de synchronisation invalide : « %s » — renvoyez le curseur émis par le serveur".formatted(
                curseur == null ? "" : curseur));
    }
}
