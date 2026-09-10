package bf.publichealth.modules.sync.domain;

import java.util.UUID;

/**
 * Appareil inconnu — le protocole exige de déclarer l'appareil
 * (POST /api/v1/sync/device) avant de l'utiliser dans un lot ou un delta.
 */
public class AppareilInconnuException extends RuntimeException {

    public AppareilInconnuException(UUID deviceId) {
        super("Appareil inconnu : %s — déclarez-le d'abord via POST /api/v1/sync/device".formatted(deviceId));
    }
}
