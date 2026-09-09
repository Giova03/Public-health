-- =====================================================================
-- V13 — Module hub (épique E3, fin) : connecteur PH HUB SORTANT.
--
-- Le hub est la frontière d'interopérabilité inter-plateformes du
-- Burkina (loi architecturale n°4 : seuls fhir et hub parlent
-- « l'étranger »). Connecteur UNIQUEMENT sortant (HTTPS 443, jamais
-- d'entrée), déployé localement.
--
-- hub.destination  : partenaires déclarés + FILIGRANE (watermark =
--                    dernier numéro de séquence ACQUITTÉ par la
--                    destination — la borne du protocole)
-- hub.message      : enveloppe HMAC-SHA256 signée, SÉQUENCE MONOTONE
--                    PAR DESTINATION, retransmission avec trempe et
--                    gigue, file morte (DLQ) après N tentatives
-- hub.delivery_log : historique append-only des tentatives (la
--                    preuve de livraison, style V3)
-- hub.simulation_reponse : pilotage du transport SIMULÉ (dev/test) —
--                    le scénario par (destination, event) y est semé
--
-- Source des événements : le transactional outbox sync.outbox (V4).
-- Le hub est le draineur OFFICIEL de cette table (porte de migration
-- Kafka) : lecture et marquage published en mono-schema par
-- l'adaptateur du module hub (patron accepté MiroirPatientsPg),
-- MÊME transaction locale — jamais de JOIN inter-schemas.
--
-- RLS : hub.message, hub.destination et hub.delivery_log ne portent
-- AUCUNE colonne d'attribution utilisateur (tables machine
-- d'interopérabilité, comme sync.outbox exclu de la RLS en V10) —
-- pas de policies utilisateur. La protection est applicative
-- (routes /api/v1/hub/** sous /api/v1/**) et protocolaire
-- (signature HMAC).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS hub;

-- ---------------------------------------------------------------------
-- Destination : un partenaire du PH HUB national (ex : 'ph-hub-national',
-- 'ph-hub-simulation'). watermark = dernier numéro de séquence ACQUITTÉ
-- par la destination (monotone : GREATEST uniquement, jamais en arrière).
-- ---------------------------------------------------------------------

CREATE TABLE hub.destination (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE,
    base_url   text NOT NULL,
    actif      boolean NOT NULL DEFAULT true,
    watermark  bigint NOT NULL DEFAULT 0 CHECK (watermark >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN hub.destination.watermark IS
    'Dernier numéro de séquence ACQUITTÉ par la destination (borne du protocole, monotone)';

-- ---------------------------------------------------------------------
-- Message : une enveloppe signée prête à partir, UNE par (événement,
-- destination) — le draineur éclate chaque événement de l'outbox vers
-- CHAQUE destination active (fan-out du protocole). event_id référence
-- sync.outbox SANS FK inter-schema (les modules ne se joignent jamais —
-- l'adaptateur hub lit l'outbox en mono-schema) ; l'unicité du couple
-- (event_id, destination_id) garantit qu'un événement n'est pris en file
-- qu'UNE fois par destination. Séquence monotone PAR DESTINATION :
-- attribuée par le draineur (verrou applicatif sur hub.destination),
-- dernière défense = la contrainte UNIQUE (destination_id, sequence)
-- ci-dessous.
-- ---------------------------------------------------------------------

CREATE TABLE hub.message (
    id              uuid PRIMARY KEY,
    event_id        uuid NOT NULL,
    destination_id  uuid NOT NULL REFERENCES hub.destination(id),
    sequence        bigint NOT NULL CHECK (sequence > 0),
    envelope        jsonb NOT NULL,
    signature       text NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'queued'
                    CHECK (status IN ('queued','sent','acked','dead')),
    attempts        int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error      text,
    next_attempt_at timestamptz,
    sent_at         timestamptz,
    acked_at        timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, destination_id),
    UNIQUE (destination_id, sequence)
);

COMMENT ON TABLE hub.message IS
    'Enveloppes HUB signées HMAC-SHA256, séquence monotone par destination, DLQ après N tentatives';

-- Messages à (re)pousser : index partiel sur les statuts ouverts.
CREATE INDEX idx_hub_message_dus
    ON hub.message (status, next_attempt_at)
    WHERE status IN ('queued','sent');

CREATE INDEX idx_hub_message_destination
    ON hub.message (destination_id, sequence DESC);

-- ---------------------------------------------------------------------
-- delivery_log : l'historique des tentatives, APPEND-ONLY (garde
-- anti-UPDATE/anti-DELETE style V3 — le journal de livraison est la
-- preuve, il se complète, jamais ne se réécrit).
-- ---------------------------------------------------------------------

CREATE TABLE hub.delivery_log (
    id         uuid PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES hub.message(id),
    attempt    int NOT NULL,
    outcome    varchar(24) NOT NULL,
    detail     text,
    at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_hub_delivery_log_message ON hub.delivery_log (message_id, attempt);

-- Garde append-only : une tentative de livraison ne se réécrit JAMAIS.
CREATE OR REPLACE FUNCTION hub.interdit_reecriture_delivery_log() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'hub.delivery_log est append-only : la mise à jour d''une tentative de livraison est interdite';
END $$ LANGUAGE plpgsql;

-- Une tentative ne se supprime pas non plus : l'historique est la preuve.
CREATE OR REPLACE FUNCTION hub.interdit_suppression_delivery_log() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'hub.delivery_log est append-only : la suppression d''une tentative de livraison est interdite';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_hub_delivery_log_no_update
    BEFORE UPDATE ON hub.delivery_log
    FOR EACH ROW EXECUTE FUNCTION hub.interdit_reecriture_delivery_log();

CREATE TRIGGER trg_hub_delivery_log_no_delete
    BEFORE DELETE ON hub.delivery_log
    FOR EACH ROW EXECUTE FUNCTION hub.interdit_suppression_delivery_log();

-- ---------------------------------------------------------------------
-- simulation_reponse : pilotage du transport SIMULÉ (défaut en dev et
-- test). Le scénario par (destination_code, event_id) y est semé par
-- INSERT/UPDATE/DELETE SQL — table machine de pilotage, hors périmètre
-- du journal append-only. En production (hub.transport=https), elle est
-- simplement ignorée par le TransportHttps.
-- ---------------------------------------------------------------------

CREATE TABLE hub.simulation_reponse (
    destination_code text NOT NULL,
    event_id         uuid NOT NULL,
    comportement     varchar(24) NOT NULL
                     CHECK (comportement IN ('ACK','ECHEC_TRANSITOIRE','REJET_DEFINITIF')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (destination_code, event_id)
);

-- ---------------------------------------------------------------------
-- Destination de développement, seedée : 'ph-hub-simulation'. Le secret
-- HMAC de dév (documenté, à remplacer en production via la propriété
-- hub.secret-destinations ou l'environnement) vit DANS LE CODE du
-- module hub, jamais en base et jamais dans application.yml.
-- ---------------------------------------------------------------------

INSERT INTO hub.destination (id, code, base_url, actif, watermark)
VALUES ('00000000-0000-0000-0000-00000000beef', 'ph-hub-simulation',
        'https://ph-hub-simulation.local/v1/evenements', true, 0)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- Octrois au rôle applicatif app_rw (créé en V10) : lecture/écriture,
-- jamais suppression. Pas de RLS (aucune colonne d'attribution).
-- ---------------------------------------------------------------------

GRANT USAGE ON SCHEMA hub TO app_rw;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA hub TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA hub
    GRANT SELECT, INSERT, UPDATE ON TABLES TO app_rw;
