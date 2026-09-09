-- =====================================================================
-- V1 — Module identity : le MPI (Master Patient Index)
-- Dossier doré patient + identifiants nationaux uniques + file de revue
-- des rapprochements + journal des fusions (raison OBLIGATOIRE).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.patient (
    id                       uuid PRIMARY KEY,
    active                   boolean NOT NULL DEFAULT true,
    gender                   varchar(16) CHECK (gender IN ('male','female','other','unknown')),
    birth_date               date,
    birth_date_approximative boolean NOT NULL DEFAULT false,
    deceased                 boolean NOT NULL DEFAULT false,
    deceased_at              timestamptz,
    master_id                uuid REFERENCES identity.patient(id),
    version                  bigint NOT NULL DEFAULT 1,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN identity.patient.master_id IS 'Non NULL => ce dossier est FUSIONNE dans master_id (irreversible, tracee dans merge_log)';

CREATE TABLE identity.patient_name (
    id         uuid PRIMARY KEY,
    patient_id uuid NOT NULL REFERENCES identity.patient(id) ON DELETE CASCADE,
    use        varchar(16) NOT NULL DEFAULT 'official' CHECK (use IN ('official','usual')),
    family     text NOT NULL,
    given      text NOT NULL
);
CREATE INDEX idx_patient_name_search ON identity.patient_name (lower(family), lower(given));

CREATE TABLE identity.patient_telecom (
    id         uuid PRIMARY KEY,
    patient_id uuid NOT NULL REFERENCES identity.patient(id) ON DELETE CASCADE,
    system     varchar(16) NOT NULL CHECK (system IN ('phone','email')),
    value      text NOT NULL,
    use        varchar(16) NOT NULL DEFAULT 'mobile'
);
CREATE INDEX idx_patient_telecom_value ON identity.patient_telecom (value);

CREATE TABLE identity.patient_address (
    id          uuid PRIMARY KEY,
    patient_id  uuid NOT NULL REFERENCES identity.patient(id) ON DELETE CASCADE,
    localite    text,
    region      text,
    country     varchar(2) NOT NULL DEFAULT 'BF'
);

CREATE TABLE identity.patient_identifier (
    id          uuid PRIMARY KEY,
    patient_id  uuid NOT NULL REFERENCES identity.patient(id) ON DELETE CASCADE,
    system      varchar(32) NOT NULL CHECK (system IN ('NUNP','CNIB','ANCIEN_REGISTRE','LOCAL')),
    value       text NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now()
);
-- Les identifiants nationaux sont uniques. LOCAL peut dupliquer
-- (numeros de registre propres a chaque structure sanitaire).
CREATE UNIQUE INDEX uniq_national_identifier
    ON identity.patient_identifier (system, value)
    WHERE system <> 'LOCAL';

-- File de revue des rapprochements (zone grise du dedoublonnage)
CREATE TABLE identity.identity_match (
    id          uuid PRIMARY KEY,
    candidate_a uuid NOT NULL REFERENCES identity.patient(id),
    candidate_b uuid NOT NULL REFERENCES identity.patient(id),
    score       numeric(4,3) NOT NULL CHECK (score BETWEEN 0 AND 1),
    method      varchar(16) NOT NULL CHECK (method IN ('EXACT','PROBABILISTIC')),
    status      varchar(16) NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    reviewed_by uuid,
    reviewed_at timestamptz,
    motif       text
);
CREATE INDEX idx_identity_match_pending ON identity.identity_match (status) WHERE status = 'PENDING';

-- Journal des fusions : la fusion est irreversible, donc invariablement tracee
CREATE TABLE identity.merge_log (
    id           uuid PRIMARY KEY,
    master_id    uuid NOT NULL REFERENCES identity.patient(id),
    merged_id    uuid NOT NULL REFERENCES identity.patient(id),
    fields       jsonb NOT NULL,
    performed_by uuid NOT NULL,
    reason       text NOT NULL,
    performed_at timestamptz NOT NULL DEFAULT now()
);
