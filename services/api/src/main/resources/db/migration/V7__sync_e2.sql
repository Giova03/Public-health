-- =====================================================================
-- V7 — Module sync (épique E2, côté API) : protocole offline complet.
--
-- 1) Miroir descendant identity.patient : la colonne updated_at existe
--    depuis V1 mais n'était maintenue que par le code Java (fusion,
--    incrément de version). Pour que le curseur delta soit STABLE quoi
--    que fasse le code applicatif futur, un trigger garantit désormais
--    sa mise à jour sur tout UPDATE. C'est une migration
--    d'infrastructure SANS JOIN inter-schemas (loi architecturale
--    respectée : aucun module voisin ne lit ni n'écrit ici).
--
--    Tombstones : pas de table dédiée — V1 fournit déjà active=false +
--    master_id pour les dossiers fusionnés. Le delta remonte CES lignes
--    telles quelles (statut inclus) : c'est le miroir client IndexedDB
--    qui purge/exclut localement les dossiers inactifs ou redirigés
--    vers leur maître. Aucune donnée sensible n'est perdue.
--
-- 2) sync.op : user_id et entity_id deviennent NULLables.
--    - user_id : le protocole E2 accepte un lot sans utilisateur connu
--      (appareil non rattaché, déclaration anonyme) ;
--    - entity_id : une création patient offline porte une clé cliente
--      (clientRequestId) — l'UUID serveur n'est connu qu'APRÈS
--      l'application ; un CONFLIT ne produit AUCUNE entité.
--
-- 3) sync.device : user_id nullable (déclaration d'appareil sans compte)
--    et l'unicité (user_id, device_name) devient insensible aux NULL
--    via un index unique sur COALESCE — rejouer la déclaration d'un
--    appareil sans utilisateur doit retourner LE MÊME deviceId.
-- =====================================================================

-- (1) Curseur stable du miroir patient ----------------------------------

CREATE OR REPLACE FUNCTION identity.maj_patient_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$ LANGUAGE plpgsql;

COMMENT ON FUNCTION identity.maj_patient_updated_at() IS
    'V7 (sync E2) : maintien automatique du curseur delta — toute mise à jour du dossier patient rafraîchit updated_at';

CREATE TRIGGER trg_patient_updated_at
    BEFORE UPDATE ON identity.patient
    FOR EACH ROW EXECUTE FUNCTION identity.maj_patient_updated_at();

-- Keyset pagination du delta : (updated_at, id) strictement croissant.
CREATE INDEX idx_patient_delta ON identity.patient (updated_at, id);

-- (2) Idempotence uplink : op jouable sans utilisateur ni entité connue -

ALTER TABLE sync.op  ALTER COLUMN user_id   DROP NOT NULL;
ALTER TABLE sync.op  ALTER COLUMN entity_id DROP NOT NULL;

COMMENT ON COLUMN sync.op.user_id IS
    'V7 : NULL = lot reçu sans utilisateur identifié (appareil anonyme)';
COMMENT ON COLUMN sync.op.entity_id IS
    'V7 : NULL = op sans entité produite (patient en CONFLIT) ou identifiant encore inconnu';

-- (3) Appareils : unicité (utilisateur, nom) insensible aux NULL -------

ALTER TABLE sync.device ALTER COLUMN user_id DROP NOT NULL;

DROP INDEX sync.uniq_sync_device_user_name;

-- COALESCE matérialise « l'utilisateur anonyme » : deux déclarations
-- sans utilisateur portant le même device_name décrivent LE MÊME
-- appareil — l'idempotence de déclaration doit tenir sans compte.
CREATE UNIQUE INDEX uniq_sync_device_user_name ON sync.device (
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    device_name
);
