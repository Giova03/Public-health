-- =====================================================================
-- V2 — Module payments : l'argent ne pardonne rien.
-- 8 etats forward-only (ADR-006) + historique des transitions (la preuve)
-- + evenements webhook dedoublonnes par eventId (idempotence).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS payments;

CREATE TABLE payments.payment (
    id              uuid PRIMARY KEY,
    -- Pas de FK inter-schema (loi n3 du monolithe modulaire) :
    -- integrite applicative + job nocturne d'audit d'integrite.
    invoice_id      uuid NOT NULL,
    amount          numeric(12,0) NOT NULL CHECK (amount > 0),
    currency        char(3) NOT NULL DEFAULT 'XOF',
    provider        varchar(32) NOT NULL DEFAULT 'FEDAPAY',
    provider_ref    text,
    idempotency_key varchar(128) NOT NULL UNIQUE,
    state           varchar(16) NOT NULL DEFAULT 'INITIATED'
                    CHECK (state IN ('INITIATED','PENDING','AUTHORIZED','SUCCEEDED',
                                     'FAILED','CANCELLED','REFUNDED','RECONCILED')),
    initiated_by    uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_invoice ON payments.payment (invoice_id);
CREATE INDEX idx_payment_provider_ref ON payments.payment (provider_ref) WHERE provider_ref IS NOT NULL;

-- L'historique des transitions EST la preuve comptable.
CREATE TABLE payments.payment_transition (
    id                bigserial PRIMARY KEY,
    payment_id        uuid NOT NULL REFERENCES payments.payment(id),
    from_state        varchar(16) NOT NULL,
    to_state          varchar(16) NOT NULL,
    reason            text,
    provider_event_id text,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_transition_payment ON payments.payment_transition (payment_id, id);

-- Idempotence des webhooks : un eventId consomme une seule fois.
CREATE TABLE payments.webhook_event (
    event_id     text PRIMARY KEY,
    type         text NOT NULL,
    payload_hash text NOT NULL,
    signature_ok boolean NOT NULL,
    status       varchar(16) NOT NULL
                 CHECK (status IN ('PROCESSED','DUPLICATED','REJECTED','ORPHAN')),
    received_at  timestamptz NOT NULL DEFAULT now()
);
