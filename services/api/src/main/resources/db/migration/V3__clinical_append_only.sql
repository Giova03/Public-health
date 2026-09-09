-- =====================================================================
-- V3 — Module clinical : append-only par conception.
-- Une observation n'est jamais reecrite, seulement annulee par
-- contre-entree explicite. C'est ce qui rend la synchronisation
-- offline sans conflit. UUID v7 generes COTE CLIENT.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS clinical;

CREATE TABLE clinical.encounter (
    id              uuid PRIMARY KEY,
    patient_id      uuid NOT NULL,
    facility_id     uuid NOT NULL,
    practitioner_id uuid,
    encounter_class varchar(24) NOT NULL,
    reason          text,
    started_at      timestamptz NOT NULL,
    ended_at        timestamptz,
    created_via     varchar(8) NOT NULL DEFAULT 'online'
                    CHECK (created_via IN ('online','offline')),
    synced_at       timestamptz
);
CREATE INDEX idx_encounter_patient ON clinical.encounter (patient_id, started_at DESC);
CREATE INDEX idx_encounter_facility ON clinical.encounter (facility_id, started_at DESC);

CREATE TABLE clinical.observation (
    id            uuid PRIMARY KEY,
    encounter_id  uuid NOT NULL REFERENCES clinical.encounter(id),
    patient_id    uuid NOT NULL,
    code          text NOT NULL,
    value_text    text,
    value_num     numeric,
    status        varchar(24) NOT NULL DEFAULT 'final'
                  CHECK (status IN ('final','entered-in-error')),
    effective_at  timestamptz NOT NULL
);

CREATE TABLE clinical.condition (
    id           uuid PRIMARY KEY,
    encounter_id uuid NOT NULL REFERENCES clinical.encounter(id),
    patient_id   uuid NOT NULL,
    code         text NOT NULL,
    clinical_status varchar(24) NOT NULL DEFAULT 'active',
    recorded_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE clinical.medication_request (
    id              uuid PRIMARY KEY,
    encounter_id    uuid NOT NULL REFERENCES clinical.encounter(id),
    patient_id      uuid NOT NULL,
    medication_text text NOT NULL,
    dose            text,
    duration_days   integer CHECK (duration_days > 0),
    instructions    text,
    status          varchar(24) NOT NULL DEFAULT 'active',
    authored_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE clinical.allergy_intolerance (
    id          uuid PRIMARY KEY,
    patient_id  uuid NOT NULL,
    substance   text NOT NULL,
    criticality varchar(16) CHECK (criticality IN ('low','high','unable-to-assess')),
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_allergy_patient ON clinical.allergy_intolerance (patient_id);

-- Garde append-only : une observation ne se réécrit JAMAIS.
-- Seule transition autorisée : final -> entered-in-error (annulation
-- explicite, tracée), à valeurs strictement inchangées.
-- Une erreur de saisie se corrige par contre-entrée, pas par réécriture :
-- c'est ce qui rend la synchronisation offline sans conflit.
CREATE OR REPLACE FUNCTION clinical.guard_append_only() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'final'
       AND NEW.status = 'entered-in-error'
       AND NEW.id IS NOT DISTINCT FROM OLD.id
       AND NEW.encounter_id IS NOT DISTINCT FROM OLD.encounter_id
       AND NEW.patient_id IS NOT DISTINCT FROM OLD.patient_id
       AND NEW.code IS NOT DISTINCT FROM OLD.code
       AND NEW.value_text IS NOT DISTINCT FROM OLD.value_text
       AND NEW.value_num IS NOT DISTINCT FROM OLD.value_num
       AND NEW.effective_at IS NOT DISTINCT FROM OLD.effective_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'clinical.observation est append-only : la réécriture est interdite (annulation = status entered-in-error)';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_observation_append_only
    BEFORE UPDATE ON clinical.observation
    FOR EACH ROW EXECUTE FUNCTION clinical.guard_append_only();

-- Aucune suppression non plus : l'historique clinique est la preuve.
CREATE OR REPLACE FUNCTION clinical.interdit_suppression() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'clinical.observation est append-only : suppression interdite';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_observation_no_delete
    BEFORE DELETE ON clinical.observation
    FOR EACH ROW EXECUTE FUNCTION clinical.interdit_suppression();
