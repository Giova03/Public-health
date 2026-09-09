-- =====================================================================
-- V12 — Épique E6 : back-office minimal (structures sanitaires,
-- utilisateurs, rôles, permissions).
--
-- 1) organization.structure : l'annuaire national des structures
--    sanitaires (CSPS, CS, CM, CHU, CHUP, CMA, privées, pharmacies)
--    avec découpage administratif du Burkina Faso et géolocalisation
--    optionnelle. Une structure fermée se DÉSACTIVE (soft), jamais de
--    DELETE (l'annuaire est la référence historique nationale).
--
-- 2) administration.utilisateur : le miroir applicatif des comptes
--    Supabase Auth. AUCUN mot de passe, AUCUN hash, AUCUN secret ne
--    transite par cette table : l'authentification vit chez Supabase,
--    on ne stocke que l'identifiant du compte (supabase_user_id, lié
--    une seule fois à l'activation) et le portrait administratif
--    (rôle, statut, MFA, structure de rattachement).
--
--    Email : CITEXT n'est PAS utilisé — décision documentée : citext
--    est une extension contrib (Supabase l'expose dans le schéma
--    `extensions`, un PostgreSQL nu exige un super-utilisateur pour
--    CREATE EXTENSION). On retient le repli portable demandé :
--    colonne text + index unique sur l'expression LOWER(email). Le
--    service normalise de surcroît l'email en minuscules à l'écriture
--    (Supabase Auth canonise de la même façon) — l'index expression
--    reste la garde pour tout écrit SQL direct.
--
-- 3) administration.role_permission : la matrice rôle → permissions,
--    valeur de référence de l'application (lue par
--    UtilisateurContexteService pour /api/v1/admin/me/permissions).
--    Semée ci-dessous avec EXACTEMENT la cartographie du domaine pur
--    RolesPermissions (modules/administration/domain) — BackofficeIT
--    verrouille l'égalité table ↔ domaine pour empêcher toute dérive.
--
-- 4) Garde anti-suppression (style V3) : on SUSPEND un utilisateur,
--    on ne le DELETE jamais — l'administratif est append-only (le
--    commentaire du trigger porte la règle).
--
-- 5) RLS style V10 : administration.utilisateur porte invited_by
--    (colonne attribuable) → ENABLE + FORCE + policies
--    app.current_user_id() = invited_by (ou rôle admin), INSERT/WITH
--    CHECK strict, AUCUNE policy DELETE. Contournement propriétaire :
--    super-utilisateur/Flyway jamais soumis (même FORCE), app_rw oui.
--    organization.structure : AUCUNE colonne attribuable
--    (created_by/user_id/...) → PAS de RLS (annuaire national lu par
--    tous les agents authentifiés, écrit par le back-office admin ;
--    le verrou d'écriture vit dans la couche HTTP /api/v1/admin/** de
--    E5 — documenté ici conformément au recensement V10).
--
--    Octrois app_rw prolongés sur les deux nouveaux schémas (V10 ne
--    couvrait pas organization/administration : ALTER DEFAULT
--    PRIVILEGES + GRANT explicites ci-dessous).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS organization;
CREATE SCHEMA IF NOT EXISTS administration;

-- ---------------------------------------------------------------------
-- (1) Annuaire des structures sanitaires
-- ---------------------------------------------------------------------

CREATE TABLE organization.structure (
    id         uuid PRIMARY KEY,          -- UUID v7
    code       text NOT NULL UNIQUE,      -- mnémonique officielle (ex. CSPS-OUA-014)
    nom        text NOT NULL,
    type       varchar(16) NOT NULL
               CHECK (type IN ('csp','cs','cm','chu','chup','cma','private','pharmacy')),
    region     text,                      -- découpage administratif BF (13 régions)
    province   text,
    commune    text,
    latitude   numeric(9,6),              -- géolocalisation optionnelle (paire exigée par le service)
    longitude  numeric(9,6),
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE organization.structure IS
    'Annuaire national des structures sanitaires — désactivation SOFT (active=false), jamais de DELETE. PAS de RLS : aucune colonne attribuable (recensement V10), lecture nationale par design, écriture réservée au back-office admin (couche HTTP E5).';

CREATE INDEX idx_structure_region_type ON organization.structure (region, type);
CREATE INDEX idx_structure_active ON organization.structure (active);

-- ---------------------------------------------------------------------
-- (2) Utilisateurs — miroir applicatif des comptes Supabase Auth
-- ---------------------------------------------------------------------

CREATE TABLE administration.utilisateur (
    id              uuid PRIMARY KEY,     -- UUID v7
    email           text NOT NULL,        -- unicité par LOWER(email) ci-dessous (citext écarté, voir en-tête)
    supabase_user_id uuid UNIQUE,         -- miroir du compte Supabase Auth, lié UNE fois à l'activation (nullable avant)
    nom             text NOT NULL,
    prenoms         text,
    structure_id    uuid,                 -- SANS FK inter-schémas (loi n°3) : référence logique organization.structure
    role            varchar(24) NOT NULL
                    CHECK (role IN ('admin','medecin','infirmier','pharmacien','agent_financier','superviseur')),
    mfa_active      boolean NOT NULL DEFAULT false,
    status          varchar(16) NOT NULL DEFAULT 'invite'
                    CHECK (status IN ('invite','actif','suspendu')),
    invited_by      uuid,                 -- QUI a invité (référence logique administration.utilisateur, nullable)
    last_login_at   timestamptz,          -- posé à l'activation (première connexion), maintenu par le flux de connexion à venir
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON COLUMN administration.utilisateur.supabase_user_id IS
    'Miroir du compte Supabase Auth — JAMAIS de mot de passe ni de hash ici : l''authentification vit chez Supabase, la liaison est définitive (UNIQUE, 409 si déjà lié)';
COMMENT ON COLUMN administration.utilisateur.structure_id IS
    'uuid SANS FK (loi n°3 : aucune FK inter-schémas) — existence vérifiée par l''application (module organization) au moment de l''invitation';

-- Unicité email insensible à la casse : garde SQL pour tout écrit direct.
CREATE UNIQUE INDEX uniq_utilisateur_email_lower
    ON administration.utilisateur (LOWER(email));

CREATE INDEX idx_utilisateur_structure ON administration.utilisateur (structure_id);
CREATE INDEX idx_utilisateur_role ON administration.utilisateur (role);

-- ---------------------------------------------------------------------
-- (3) Matrice rôle → permissions (valeur de référence applicative)
-- ---------------------------------------------------------------------

CREATE TABLE administration.role_permission (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role       varchar(24) NOT NULL
               CHECK (role IN ('admin','medecin','infirmier','pharmacien','agent_financier','superviseur')),
    permission text NOT NULL,
    UNIQUE (role, permission)
);
COMMENT ON TABLE administration.role_permission IS
    'Matrice rôle → permissions, semée par V12 à l''identique du domaine pur RolesPermissions (contrôle d''égalité par BackofficeIT)';

INSERT INTO administration.role_permission (role, permission) VALUES
    -- admin : tout le back-office + supervision globale
    ('admin', 'patient:lire'),
    ('admin', 'patient:ecrire'),
    ('admin', 'prescription:lire'),
    ('admin', 'prescription:ecrire'),
    ('admin', 'dispenser'),
    ('admin', 'paiement:initier'),
    ('admin', 'paiement:lire'),
    ('admin', 'paiement:reconcilier'),
    ('admin', 'audit:lire'),
    ('admin', 'admin:gerer'),
    -- medecin : dossier patient + prescription
    ('medecin', 'patient:lire'),
    ('medecin', 'patient:ecrire'),
    ('medecin', 'prescription:lire'),
    ('medecin', 'prescription:ecrire'),
    -- infirmier : admission MPI + frais d'accès au comptoir
    ('infirmier', 'patient:lire'),
    ('infirmier', 'patient:ecrire'),
    ('infirmier', 'paiement:initier'),
    -- pharmacien : dispensation
    ('pharmacien', 'patient:lire'),
    ('pharmacien', 'prescription:lire'),
    ('pharmacien', 'dispenser'),
    -- agent_financier : paiements et facturation
    ('agent_financier', 'patient:lire'),
    ('agent_financier', 'paiement:initier'),
    ('agent_financier', 'paiement:lire'),
    -- superviseur : supervision en lecture (paiements, audit)
    ('superviseur', 'patient:lire'),
    ('superviseur', 'prescription:lire'),
    ('superviseur', 'paiement:lire'),
    ('superviseur', 'audit:lire');

-- ---------------------------------------------------------------------
-- (4) Garde anti-suppression — append-only administratif (style V3)
--
-- Un utilisateur ne se supprime JAMAIS : on le SUSPEND (status
-- suspendu, motif tracé en audit). L'historique administratif — qui
-- a invité, qui a activé, qui a suspendu, pourquoi — est la preuve
-- comptable et légale du back-office. structure_id et invited_by
-- restent des références logiques stables précisément parce que
-- personne ne disparaît.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION administration.interdit_suppression() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'administration.utilisateur est append-only : suppression interdite — on SUSPEND le compte (status suspendu), on ne DELETE jamais';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_utilisateur_no_delete
    BEFORE DELETE ON administration.utilisateur
    FOR EACH ROW EXECUTE FUNCTION administration.interdit_suppression();

-- ---------------------------------------------------------------------
-- (5) RLS — administration.utilisateur (invited_by, style V10)
--
-- Sémantique identique aux dix tables attribuables de V10 :
--   SELECT : app.current_user_id() = invited_by OU rôle admin
--   INSERT : app.current_user_id() = invited_by (fail-closed sinon)
--   UPDATE : idem SELECT pour USING et WITH CHECK
--   DELETE : AUCUNE policy (et le trigger ci-dessus crie de toute façon)
-- Une ligne dont invited_by est NULL n'appartient à personne :
-- invisible pour app_rw (fail-closed), lisible par le
-- propriétaire/super-utilisateur. Le modèle « équipe/structure »
-- complet arrive avec le RBAC E6 — P0 : invitant + admin.
-- ---------------------------------------------------------------------

ALTER TABLE administration.utilisateur ENABLE ROW LEVEL SECURITY;
ALTER TABLE administration.utilisateur FORCE ROW LEVEL SECURITY;

CREATE POLICY utilisateur_proprietaire_select ON administration.utilisateur
    FOR SELECT
    USING (app.current_user_id() = invited_by OR app.has_role(ARRAY['admin']));
CREATE POLICY utilisateur_proprietaire_insert ON administration.utilisateur
    FOR INSERT
    WITH CHECK (app.current_user_id() = invited_by);
CREATE POLICY utilisateur_proprietaire_update ON administration.utilisateur
    FOR UPDATE
    USING (app.current_user_id() = invited_by OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = invited_by OR app.has_role(ARRAY['admin']));

-- organization.structure : PAS de RLS — aucune colonne attribuable
-- (recensement V10), lecture nationale par design, écriture back-office
-- admin verrouillée à la couche HTTP (/api/v1/admin/** exige ROLE_ADMIN
-- quand le JWT est actif ; POST/PATCH/activate de l'annuaire vivent sous
-- /api/v1/organizations, annuaire géré par l'administration).

-- ---------------------------------------------------------------------
-- (6) Octrois app_rw — V10 ne couvrait pas ces deux schémas
-- ---------------------------------------------------------------------

GRANT USAGE ON SCHEMA organization, administration TO app_rw;

GRANT SELECT, INSERT, UPDATE ON organization.structure TO app_rw;
GRANT SELECT, INSERT, UPDATE ON administration.utilisateur TO app_rw;
GRANT SELECT, INSERT, UPDATE ON administration.role_permission TO app_rw;

ALTER DEFAULT PRIVILEGES IN SCHEMA organization
    GRANT SELECT, INSERT, UPDATE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA administration
    GRANT SELECT, INSERT, UPDATE ON TABLES TO app_rw;
