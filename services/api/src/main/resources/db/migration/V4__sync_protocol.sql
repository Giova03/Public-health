-- =====================================================================
-- V4 — Module sync : protocole offline.
-- sync.op      : idempotence de l'uplink (un opId ne s'applique qu'une fois)
-- sync.device  : appareils declarés + curseur de synchronisation
-- sync.outbox  : outbox transactionnel (evenements ecrits dans la meme
--                transaction que le fait metier — porte de migration Kafka)
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS sync;

CREATE TABLE sync.op (
    op_id        uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    entity       varchar(48) NOT NULL,
    entity_id    uuid NOT NULL,
    result       varchar(16) NOT NULL
                 CHECK (result IN ('APPLIED','REJECTED','CONFLICT')),
    result_detail jsonb,
    received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sync_op_user ON sync.op (user_id, received_at DESC);

CREATE TABLE sync.device (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL,
    device_name      text,
    last_sync_cursor text,
    last_seen_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uniq_sync_device_user_name ON sync.device (user_id, device_name);

CREATE TABLE sync.outbox (
    event_id     uuid PRIMARY KEY,
    event_type   varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload      jsonb NOT NULL,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    published    boolean NOT NULL DEFAULT false
);
CREATE INDEX idx_outbox_unpublished ON sync.outbox (occurred_at) WHERE published = false;
