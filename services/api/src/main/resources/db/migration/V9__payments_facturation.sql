-- =====================================================================
-- V9 — Module payments, épique E4 : facturation, réconciliation nocturne
-- (fait foi), orphelins, fournisseur simulé.
--
-- Architecture (ADR-006) : le webhook accélère, la réconciliation
-- nocturne AVEC le prestataire fait foi. Chaque passage laisse une
-- reconciliation_run (compteurs) et, si besoin, des
-- reconciliation_discrepancy — trace DURABLE des écarts, y compris les
-- webhooks orphelins (référence sans paiement connu) qui seront résolus
-- plus tard quand le paiement apparaîtra.
--
-- Loi n°3 du monolithe modulaire : patient_id et encounter_id sont des
-- uuid nus SANS FK inter-schémas (identité vérifiée applicativement,
-- aucun JOIN direct). Les FK INTERNES au schéma payments restent légales.
--
-- LIEN PAIEMENT↔FACTURE : la colonne payments.payment.invoice_id existe
-- DÈS V2 (uuid nu NOT NULL, référence logique de facturation fournie à
-- l'initiation). V9 ne crée donc AUCUNE colonne de doublon : le
-- rapprochement (InvoiceService) relit cette colonne. Aucune FK n'est
-- ajoutée sur elle : l'initiation d'un paiement (contrat V2, inchangé)
-- accepte une référence de facture externe ou pas encore créée — une FK
-- casserait ce contrat et les flux existants.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Facture : mutable par transitions, voided TERMINAL.
-- ---------------------------------------------------------------------
CREATE TABLE payments.invoice (
    id                uuid PRIMARY KEY,          -- UUID v7
    patient_id        uuid NOT NULL,             -- SANS FK (loi n°3, uuid nu)
    encounter_id      uuid,                      -- nullable, SANS FK (schéma clinical)
    status            varchar(24) NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','issued','partially_paid','paid','voided')),
    currency          char(3) NOT NULL DEFAULT 'XOF',
    total             numeric(12,2) NOT NULL CHECK (total >= 0),
    issued_at         timestamptz,               -- NULL tant que draft
    voided_reason     text,                      -- motif OBLIGATOIRE à l'annulation
    voided_at         timestamptz,
    client_request_id uuid UNIQUE,               -- idempotence de création (patron V6/V8)
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid
);
COMMENT ON COLUMN payments.invoice.patient_id IS
    'uuid nu SANS FK inter-schémas (loi n°3) — cohérence applicative, aucun JOIN direct';
COMMENT ON COLUMN payments.invoice.total IS
    'Total calculé SERVEUR depuis les lignes (InvoiceTotals), arrondi 2 décimales';
COMMENT ON COLUMN payments.invoice.voided_reason IS
    'Motif OBLIGATOIRE de l''annulation — voided est TERMINAL';

CREATE INDEX idx_invoice_patient ON payments.invoice (patient_id, issued_at DESC);

-- Les lignes font la preuve du montant : append-only.
CREATE TABLE payments.invoice_item (
    id         uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES payments.invoice(id),
    label      text NOT NULL,
    quantity   numeric(12,2) NOT NULL CHECK (quantity > 0),
    unit_price numeric(12,2) NOT NULL CHECK (unit_price >= 0)
);
CREATE INDEX idx_invoice_item_invoice ON payments.invoice_item (invoice_id);

-- ---------------------------------------------------------------------
-- Réconciliation nocturne : chaque passage est un run comptable.
-- ---------------------------------------------------------------------
CREATE TABLE payments.reconciliation_run (
    id           uuid PRIMARY KEY,
    started_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    counts       jsonb,       -- {examined, confirmed, advanced, failed, ignored, orphans_resolved}
    triggered_by varchar(24) NOT NULL
                 CHECK (triggered_by IN ('schedule','manuel')),
    detail       jsonb
);
CREATE INDEX idx_reconciliation_run_started ON payments.reconciliation_run (started_at DESC);

-- Trace durable des écarts : rétrogradations ignorées (forward-only),
-- états inconnus, montants divergents, webhooks orphelins (run_id NULL :
-- constatés à la réception du webhook, résolus par un run ultérieur).
CREATE TABLE payments.reconciliation_discrepancy (
    id          uuid PRIMARY KEY,
    run_id      uuid REFERENCES payments.reconciliation_run(id),
    payment_id  uuid,
    reference   text,
    kind        varchar(32) NOT NULL,
    description text,
    resolved    boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_discrepancy_run
    ON payments.reconciliation_discrepancy (run_id);
CREATE INDEX idx_reconciliation_discrepancy_ouvertes
    ON payments.reconciliation_discrepancy (resolved) WHERE NOT resolved;

-- ---------------------------------------------------------------------
-- Fournisseur simulé (tests/dev) : le statut distant par référence est
-- semé ici — déterministe, aucune dépendance réseau. Utilisé par défaut
-- tant que fedapay.api-url n'est pas configuré.
-- ---------------------------------------------------------------------
CREATE TABLE payments.provider_simulation (
    reference      text PRIMARY KEY,
    status         varchar(16) NOT NULL
                   CHECK (status IN ('SUCCEEDED','PENDING','FAILED','CANCELLED')),
    amount         numeric(12,2),
    provider_tx_id text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- =====================================================================
-- Gardes (style V3/V8) : le schéma porte la loi architecturale.
-- =====================================================================

-- Garde facture : l'identité ne se réécrit jamais ; seules transitions
-- légales — émission (draft→issued), annulation (draft|issued→voided,
-- motif + horodatage, TERMINALE), encaissement (issued→partially_paid|paid,
-- partially_paid→paid). voided et paid ne bougent plus JAMAIS ; aucun
-- retour en arrière n'existe.
CREATE OR REPLACE FUNCTION payments.guard_invoice() RETURNS trigger AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
       OR NEW.encounter_id IS DISTINCT FROM OLD.encounter_id
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.total IS DISTINCT FROM OLD.total
       OR NEW.client_request_id IS DISTINCT FROM OLD.client_request_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'payments.invoice : réécriture interdite — l''identité de la facture est immuable';
    END IF;

    IF OLD.status = 'draft' AND NEW.status = 'issued'
       AND NEW.issued_at IS NOT NULL
       AND NEW.voided_reason IS NULL AND NEW.voided_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status IN ('draft','issued') AND NEW.status = 'voided'
       AND NEW.voided_reason IS NOT NULL AND NEW.voided_at IS NOT NULL
       AND NEW.issued_at IS NOT DISTINCT FROM OLD.issued_at THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'issued' AND NEW.status IN ('partially_paid','paid')
       AND NEW.issued_at IS NOT DISTINCT FROM OLD.issued_at
       AND NEW.voided_reason IS NULL AND NEW.voided_at IS NULL THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'partially_paid' AND NEW.status = 'paid'
       AND NEW.issued_at IS NOT DISTINCT FROM OLD.issued_at
       AND NEW.voided_reason IS NULL AND NEW.voided_at IS NULL THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'payments.invoice : transition illégale (% -> %) — voided et paid sont terminaux, aucun retour en arrière', OLD.status, NEW.status;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_garde
    BEFORE UPDATE ON payments.invoice
    FOR EACH ROW EXECUTE FUNCTION payments.guard_invoice();

-- Une facture ne se supprime pas : l'historique comptable est la preuve.
-- Une ligne de facture ne se réécrit ni ne se supprime (append-only) :
-- corriger un brouillon = annuler la facture et en émettre une nouvelle.
CREATE OR REPLACE FUNCTION payments.interdit_suppression() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payments.% : suppression interdite — l''historique est la preuve', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION payments.interdit_reecriture() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payments.% est append-only : la mise à jour est interdite', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invoice_no_delete
    BEFORE DELETE ON payments.invoice
    FOR EACH ROW EXECUTE FUNCTION payments.interdit_suppression();

CREATE TRIGGER trg_invoice_item_no_update
    BEFORE UPDATE ON payments.invoice_item
    FOR EACH ROW EXECUTE FUNCTION payments.interdit_reecriture();

CREATE TRIGGER trg_invoice_item_no_delete
    BEFORE DELETE ON payments.invoice_item
    FOR EACH ROW EXECUTE FUNCTION payments.interdit_suppression();
