-- =====================================================================
-- V8 — Module prescription (épique E3) : prescriptions & dispensation.
--
-- Append-only par conception (même ADR que le module clinical V3) :
-- une prescription n'est jamais réécrite ni détruite. Elle est annulée
-- par contre-entrée explicite (cancelled / entered-in-error, motif
-- OBLIGATOIRE). C'est ce qui rend la synchronisation offline sans conflit.
--
-- La dispensation est partielle et CUMULÉE : plusieurs délivrances
-- successives jusqu'à épuisement de la quantité prescrite, jamais
-- au-delà (règle portée par le domaine, DispensingRules).
--
-- UUID v7 : générés côté CLIENT en mode offline, côté serveur sinon.
-- client_request_id UNIQUE : idempotence de rejeu (patron V6).
--
-- Loi n°3 du monolithe modulaire : patient_id et encounter_id sont des
-- colonnes uuid SANS FK inter-schémas — la cohérence est assurée par
-- l'application (port PatientLookup vers le module identity, aucun
-- JOIN direct). Les FK internes au schéma prescription restent légales.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS prescription;

CREATE TABLE prescription.prescription (
    id                uuid PRIMARY KEY,          -- UUID v7 (client offline, serveur sinon)
    patient_id        uuid NOT NULL,             -- SANS FK : vérifié par l'application (module identity)
    encounter_id      uuid,                      -- nullable, SANS FK (schéma clinical)
    prescriber_id     uuid,
    facility_id       uuid,
    status            varchar(24) NOT NULL DEFAULT 'active'
                      CHECK (status IN ('active','cancelled','entered-in-error')),
    issued_at         timestamptz NOT NULL,
    created_via       varchar(8) NOT NULL DEFAULT 'online'
                      CHECK (created_via IN ('online','offline')),
    client_request_id uuid UNIQUE,               -- idempotence offline (patron V6)
    cancel_reason     text,
    cancelled_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN prescription.prescription.patient_id IS
    'uuid SANS FK (loi n°3 : aucune FK inter-schémas) — existence vérifiée par le module identity au moment de la création';
COMMENT ON COLUMN prescription.prescription.cancel_reason IS
    'Motif OBLIGATOIRE de la contre-entrée (cancelled ou entered-in-error)';

CREATE INDEX idx_prescription_patient
    ON prescription.prescription (patient_id, issued_at DESC);

CREATE TABLE prescription.prescription_item (
    id                  uuid PRIMARY KEY,
    prescription_id     uuid NOT NULL REFERENCES prescription.prescription(id),
    medication_code     text NOT NULL,
    medication_label    text NOT NULL,
    dose                text,
    form                text,
    route               text,
    frequency           text,
    duration_days       integer,
    quantity_prescribed numeric(12,2) NOT NULL CHECK (quantity_prescribed > 0)
);
CREATE INDEX idx_prescription_item_prescription
    ON prescription.prescription_item (prescription_id);

CREATE TABLE prescription.dispensation (
    id                uuid PRIMARY KEY,
    prescription_id   uuid NOT NULL REFERENCES prescription.prescription(id),
    item_id           uuid NOT NULL REFERENCES prescription.prescription_item(id),
    quantity          numeric(12,2) NOT NULL CHECK (quantity > 0),
    dispensed_by      uuid,
    dispensed_at      timestamptz NOT NULL DEFAULT now(),
    client_request_id uuid UNIQUE,               -- idempotence offline (patron V6)
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispensation_prescription
    ON prescription.dispensation (prescription_id);
CREATE INDEX idx_dispensation_item
    ON prescription.dispensation (item_id);

-- =====================================================================
-- Gardes append-only (style V3) : le schéma porte la loi architecturale.
-- =====================================================================

-- Garde prescription : la réécriture est interdite. Seule transition
-- autorisée : active -> cancelled ou entered-in-error (annulation par
-- contre-entrée, motif + horodatage), à valeurs cliniques strictement
-- inchangées par ailleurs. Une erreur de saisie se corrige par
-- contre-entrée, pas par réécriture.
CREATE OR REPLACE FUNCTION prescription.guard_append_only() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'active'
       AND NEW.status IN ('cancelled','entered-in-error')
       AND NEW.id IS NOT DISTINCT FROM OLD.id
       AND NEW.patient_id IS NOT DISTINCT FROM OLD.patient_id
       AND NEW.encounter_id IS NOT DISTINCT FROM OLD.encounter_id
       AND NEW.prescriber_id IS NOT DISTINCT FROM OLD.prescriber_id
       AND NEW.facility_id IS NOT DISTINCT FROM OLD.facility_id
       AND NEW.issued_at IS NOT DISTINCT FROM OLD.issued_at
       AND NEW.created_via IS NOT DISTINCT FROM OLD.created_via
       AND NEW.client_request_id IS NOT DISTINCT FROM OLD.client_request_id
       AND NEW.created_at IS NOT DISTINCT FROM OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'prescription.prescription est append-only : la réécriture est interdite (annulation = status cancelled ou entered-in-error)';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prescription_append_only
    BEFORE UPDATE ON prescription.prescription
    FOR EACH ROW EXECUTE FUNCTION prescription.guard_append_only();

-- Garde dispensation & lignes : un fait accompli ne se réécrit pas, ne
-- se supprime pas. Le cumul des dispensations fait foi ; une erreur de
-- saisie se corrige par contre-entrée sur la prescription
-- (entered-in-error), jamais en silence. Le message porte la table fautive.
CREATE OR REPLACE FUNCTION prescription.interdit_reecriture() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'prescription.% est append-only : la mise à jour est interdite', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION prescription.interdit_suppression() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'prescription.% est append-only : suppression interdite', TG_TABLE_NAME;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dispensation_no_update
    BEFORE UPDATE ON prescription.dispensation
    FOR EACH ROW EXECUTE FUNCTION prescription.interdit_reecriture();

CREATE TRIGGER trg_dispensation_no_delete
    BEFORE DELETE ON prescription.dispensation
    FOR EACH ROW EXECUTE FUNCTION prescription.interdit_suppression();

CREATE TRIGGER trg_prescription_item_no_update
    BEFORE UPDATE ON prescription.prescription_item
    FOR EACH ROW EXECUTE FUNCTION prescription.interdit_reecriture();

CREATE TRIGGER trg_prescription_item_no_delete
    BEFORE DELETE ON prescription.prescription_item
    FOR EACH ROW EXECUTE FUNCTION prescription.interdit_suppression();

-- L'historique des prescriptions est la preuve : aucune suppression.
CREATE TRIGGER trg_prescription_no_delete
    BEFORE DELETE ON prescription.prescription
    FOR EACH ROW EXECUTE FUNCTION prescription.interdit_suppression();
