-- =====================================================================
-- V5 — Audit six dimensions (QUI/QUOI/QUAND/OU/POURQUOI/RESULTAT)
-- + fonctions applicatives + RLS (Row Level Security).
-- Append-only, chaînage par hachage : chaque entree scelle la precedente.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.entry (
    id          bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_id    uuid,                          -- QUI
    action      varchar(64) NOT NULL,          -- QUOI (ex: PATIENT_READ, PAYMENT_TRANSITION)
    entity      varchar(64) NOT NULL,
    entity_id   uuid,
    facility_id uuid,                          -- OU (structure sanitaire)
    reason      text,                          -- POURQUOI (obligatoire pour break-the-glass)
    result      varchar(16) NOT NULL
                CHECK (result IN ('SUCCESS','FAILURE','DENIED')),
    details     jsonb,                         -- RESULTAT (detail supplementaire)
    prev_hash   text,
    hash        text NOT NULL
);
CREATE INDEX idx_audit_entity ON audit.entry (entity, entity_id);
CREATE INDEX idx_audit_occurred ON audit.entry (occurred_at DESC);

-- Le journal est append-only : aucune mise a jour, aucune suppression.
REVOKE UPDATE, DELETE ON audit.entry FROM PUBLIC;

-- =====================================================================
-- Fonctions applicatives (indépendantes de Supabase : fonctionnent sur
-- PostgreSQL standard et resteront valables sur Supabase Auth / Keycloak).
-- Le contexte est porté par la variable de session 'app.*'.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS app;

CREATE OR REPLACE FUNCTION app.current_user_id() RETURNS uuid AS $$
    SELECT nullif(current_setting('app.user_id', true), '')::uuid;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION app.current_facility_ids() RETURNS uuid[] AS $$
    SELECT string_to_array(nullif(current_setting('app.facility_ids', true), ''), ',')::uuid[];
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION app.has_role(roles text[]) RETURNS boolean AS $$
    SELECT EXISTS (
        SELECT 1 FROM unnest(string_to_array(
            coalesce(current_setting('app.roles', true), ''), ',')) AS r
        WHERE r = ANY (roles)
    );
$$ LANGUAGE sql STABLE;

-- =====================================================================
-- RLS — plan 3 de la defense en profondeur (ADR-011 : sans Cloudflare,
-- la base porte une part plus grande du poids securitaire).
-- =====================================================================

-- MPI : demographie lisible nationalement (sinon pas de patient national)
ALTER TABLE identity.patient ENABLE ROW LEVEL SECURITY;
CREATE POLICY patient_read ON identity.patient
    FOR SELECT USING (true);

-- Clinique : scope par structure sanitaire
ALTER TABLE clinical.encounter ENABLE ROW LEVEL SECURITY;
CREATE POLICY encounter_select ON clinical.encounter
    FOR SELECT USING (
        facility_id = ANY (app.current_facility_ids())
        OR app.has_role(ARRAY['admin'])
    );

-- Donnees de soin sensibles : meme scope
ALTER TABLE clinical.observation ENABLE ROW LEVEL SECURITY;
CREATE POLICY observation_select ON clinical.observation
    FOR SELECT USING (
        patient_id IN (
            SELECT patient_id FROM clinical.encounter
            WHERE facility_id = ANY (app.current_facility_ids())
        )
        OR app.has_role(ARRAY['admin'])
    );

-- Audit : lecture admin uniquement, ecriture libre pour l'applicatif
ALTER TABLE audit.entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_select ON audit.entry
    FOR SELECT USING (app.has_role(ARRAY['admin']));
CREATE POLICY audit_insert ON audit.entry
    FOR INSERT WITH CHECK (true);
