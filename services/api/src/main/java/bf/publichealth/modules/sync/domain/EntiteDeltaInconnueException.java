package bf.publichealth.modules.sync.domain;

/**
 * Entité non supportée par le miroir descendant (E2 : patient seul ;
 * le clinical suivra avec l'épique E3 côté miroir).
 */
public class EntiteDeltaInconnueException extends RuntimeException {

    public EntiteDeltaInconnueException(String entite) {
        super("Entité non supportée par le delta : « %s » (supportées : patient)".formatted(entite));
    }
}
