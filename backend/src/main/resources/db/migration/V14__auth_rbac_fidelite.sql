-- =====================================================================
-- V14 — AUTH INTERNE + RBAC ÉTENDU + MODULES DE FIDÉLITÉ (RDV, LABO,
-- STOCK, RÉFÉRENCE). Correction des insuffisances I1-I3, I5-I8, I11-I12
-- de l'audit de fidélité (septembre 2026).
--
-- Contenu :
--  (1) auth interne : colonne mot_de_passe_hash (BCrypt) — corrige I3 ;
--  (2) rôle agent_saisie + permissions étendues (matrice 7 × 16) —
--      corrige I2/I12 : l'infirmier CSPS retrouve SES casquettes réelles
--      (consultation, dispensation, caisse, référence, labo) ;
--  (3) comptes et structures de démonstration pour la chaîne d'auth ;
--  (4) rendezvous.rendez_vous — machine à états demande→confirme→honoré
--      /annulé/absent, RDV passé immuable ;
--  (5) laboratoire.examen — TDR et analyses, résultat lié au diagnostic ;
--  (6) pharmacie.stock_item + pharmacie.mouvement (append-only) — la
--      dispensation est désormais adossée au stock réel (I8) ;
--  (7) reference.fiche — référence/contre-référence CSPS↔CMA/CHR (I7) ;
--  (8) décès : les colonnes deceased/deceased_at existent depuis V1 —
--      V14 n'ajoute que cause_deces pour le rapport de mortalité (I15).
-- =====================================================================

-- ---------------------------------------------------------------------
-- (1) AUTH INTERNE — hash BCrypt sur le miroir utilisateur
-- ---------------------------------------------------------------------

ALTER TABLE administration.utilisateur
    ADD COLUMN IF NOT EXISTS mot_de_passe_hash text;

COMMENT ON COLUMN administration.utilisateur.mot_de_passe_hash IS
    'Hash BCrypt — auth interne P0 de l''audit (I3). La dépendance Supabase reste possible (RS256/JWKS) mais l''API sait désormais émettre et vérifier ses propres jetons HS256. NULL = compte non encore initialisé.';

-- Mise à jour du commentaire d'en-tête (supabase_user_id) : sub = identité.
COMMENT ON COLUMN administration.utilisateur.supabase_user_id IS
    'Identité du compte (unique). Historiquement miroir Supabase Auth ; sert désormais aussi de claim sub aux jetons internes HS256 émis par /api/v1/auth/login.';

-- ---------------------------------------------------------------------
-- (2) RBAC étendu — rôle agent_saisie + 6 nouvelles permissions
-- ---------------------------------------------------------------------

ALTER TABLE administration.utilisateur DROP CONSTRAINT IF EXISTS utilisateur_role_check;
ALTER TABLE administration.utilisateur
    ADD CONSTRAINT utilisateur_role_check
    CHECK (role IN ('admin','medecin','infirmier','pharmacien','agent_financier','superviseur','agent_saisie'));

ALTER TABLE administration.role_permission DROP CONSTRAINT IF EXISTS role_permission_role_check;
ALTER TABLE administration.role_permission
    ADD CONSTRAINT role_permission_role_check
    CHECK (role IN ('admin','medecin','infirmier','pharmacien','agent_financier','superviseur','agent_saisie'));

-- Semis des nouvelles permissions (V12 a posé les 10 anciennes —
-- la matrice finale vit à l'IDENTIQUE dans RolesPermissions.java,
-- contrôle d'égalité par BackofficeIT).
INSERT INTO administration.role_permission (role, permission) VALUES
    -- admin : les 16
    ('admin', 'consultation:lire'),
    ('admin', 'consultation:ecrire'),
    ('admin', 'stock:gerer'),
    ('admin', 'rendezvous:gerer'),
    ('admin', 'reference:gerer'),
    ('admin', 'laboratoire:ecrire'),
    -- agent_saisie (NOUVEAU rôle) : admission MPI uniquement
    ('agent_saisie', 'patient:lire'),
    ('agent_saisie', 'patient:ecrire'),
    -- infirmier/ICP : le BUNDLE complet du CSPS réel (I12) — consultation,
    -- prescription, dispensation, caisse, RDV, référence, labo
    ('infirmier', 'consultation:lire'),
    ('infirmier', 'consultation:ecrire'),
    ('infirmier', 'prescription:lire'),
    ('infirmier', 'prescription:ecrire'),
    ('infirmier', 'dispenser'),
    ('infirmier', 'rendezvous:gerer'),
    ('infirmier', 'reference:gerer'),
    ('infirmier', 'laboratoire:ecrire'),
    -- medecin : clinical complet + référence + labo
    ('medecin', 'consultation:lire'),
    ('medecin', 'consultation:ecrire'),
    ('medecin', 'rendezvous:gerer'),
    ('medecin', 'reference:gerer'),
    ('medecin', 'laboratoire:ecrire'),
    -- pharmacien : dispensation ADOSSÉE AU STOCK (I8)
    ('pharmacien', 'stock:gerer'),
    -- superviseur : lecture clinique + référence + audit (SNIS vient
    -- par-dessus via /api/v1/statistiques, permission audit:lire)
    ('superviseur', 'consultation:lire'),
    ('superviseur', 'reference:gerer')
ON CONFLICT (role, permission) DO NOTHING;

COMMENT ON TABLE administration.role_permission IS
    'Matrice rôle → permissions (7 rôles × 16 permissions depuis V14), miroir exact du domaine pur RolesPermissions — contrôlée par BackofficeIT. Intercepteur HTTP FiltrePermissions : chaque route /api/v1/** exige sa permission (I2 corrigée).';

-- ---------------------------------------------------------------------
-- (3) Structures + comptes de démonstration (chaîne d'auth réelle)
-- ---------------------------------------------------------------------

INSERT INTO organization.structure (id, code, nom, type, region, active) VALUES
    ('11111111-1111-4111-8111-111111111101', 'CSPS-OUA-012', 'CSPS Ouaga 12', 'csp', 'Centre', true),
    ('11111111-1111-4111-8111-111111111102', 'CMA-KOS-001',  'CMA Kossodo',   'cma', 'Centre', true),
    ('11111111-1111-4111-8111-111111111103', 'CHU-YAL-001',  'CHU Yalgado Ouédraogo', 'chu', 'Centre', true),
    ('11111111-1111-4111-8111-111111111104', 'CHR-OUA-004',  'CHR Ouahigouya', 'chu', 'Nord', true)
ON CONFLICT (code) DO NOTHING;

-- Un compte par rôle, mot de passe commun « Demo1234! » (BCrypt).
-- MFA activée sur admin pour démontrer le second facteur.
INSERT INTO administration.utilisateur
    (id, email, supabase_user_id, nom, prenoms, structure_id, role, mfa_active, status, mot_de_passe_hash)
VALUES
    ('22222222-2222-4222-8222-222222222201', 'agent.saisie@demo.bf',
     '22222222-2222-4222-8222-222222222201', 'Traoré', 'Salif',
     '11111111-1111-4111-8111-111111111101', 'agent_saisie', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222202', 'infirmier@demo.bf',
     '22222222-2222-4222-8222-222222222202', 'Sawadogo', 'Aminata',
     '11111111-1111-4111-8111-111111111101', 'infirmier', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222203', 'medecin@demo.bf',
     '22222222-2222-4222-8222-222222222203', 'Kaboré', 'Boureima',
     '11111111-1111-4111-8111-111111111102', 'medecin', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222204', 'pharmacien@demo.bf',
     '22222222-2222-4222-8222-222222222204', 'Ouédraogo', 'Fatimata',
     '11111111-1111-4111-8111-111111111102', 'pharmacien', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222205', 'caissier@demo.bf',
     '22222222-2222-4222-8222-222222222205', 'Zongo', 'Issa',
     '11111111-1111-4111-8111-111111111101', 'agent_financier', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222206', 'superviseur@demo.bf',
     '22222222-2222-4222-8222-222222222206', 'Compaoré', 'Rasmané',
     '11111111-1111-4111-8111-111111111103', 'superviseur', false, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme'),
    ('22222222-2222-4222-8222-222222222207', 'admin@demo.bf',
     '22222222-2222-4222-8222-222222222207', 'Nikièma', 'Chantal',
     '11111111-1111-4111-8111-111111111103', 'admin', true, 'actif',
     '$2a$10$MpzI0Cu0AkNDLfxq5LfRGuQrXm8iEZ7jocjVcHZ9bLZTypjy4Xyme')
ON CONFLICT DO NOTHING;

COMMENT ON COLUMN administration.utilisateur.last_login_at IS
    'Maintenu par /api/v1/auth/login depuis V14 (flux de connexion réel — I3 corrigée).';

-- ---------------------------------------------------------------------
-- (4) RENDEZ-VOUS — machine à états, immuable une fois passé
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS rendezvous;

CREATE TABLE rendezvous.rendez_vous (
    id              uuid PRIMARY KEY,
    patient_id      uuid NOT NULL,
    structure_id    uuid NOT NULL,
    practitioner_id uuid,
    type            varchar(24) NOT NULL DEFAULT 'general'
                    CHECK (type IN ('general','cpn','vaccination','controle','suivi')),
    creneau         timestamptz NOT NULL,
    statut          varchar(16) NOT NULL DEFAULT 'demande'
                    CHECK (statut IN ('demande','confirme','honore','annule','absent')),
    motif           text,
    demande_par     varchar(16) NOT NULL CHECK (demande_par IN ('patient','agent')),
    motif_annulation text,
    annule_par      uuid,
    client_request_id uuid UNIQUE,           -- idempotence offline (patron V6)
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_rdv_patient ON rendezvous.rendez_vous (patient_id, creneau DESC);
CREATE INDEX idx_rdv_structure ON rendezvous.rendez_vous (structure_id, creneau DESC);
CREATE INDEX idx_rdv_statut ON rendezvous.rendez_vous (statut);

COMMENT ON TABLE rendezvous.rendez_vous IS
    'RDV — un patient demande (max 1/jour), un agent confirme, honore ou annule (motif OBLIGATOIRE). Un RDV passé est IMMUABLE : les transitions ne sont légales qu''avant le créneau (I10 : règle selon l''état temporel).';

-- ---------------------------------------------------------------------
-- (5) LABORATOIRE — TDR et analyses liés à la consultation
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS laboratoire;

CREATE TABLE laboratoire.examen (
    id              uuid PRIMARY KEY,
    patient_id      uuid NOT NULL,
    consultation_id uuid,                    -- clinical.encounter, sans FK inter-schémas (loi n°3)
    structure_id    uuid NOT NULL,
    type            varchar(32) NOT NULL
                    CHECK (type IN ('tdr_paludisme','goutte_epaisse','nfs','glycemie','urine','hiv','syphilis','autre')),
    statut          varchar(16) NOT NULL DEFAULT 'demande'
                    CHECK (statut IN ('demande','resultat','annule')),
    resultat_text   text,
    resultat_positif boolean,
    demande_le      timestamptz NOT NULL DEFAULT now(),
    resultat_le     timestamptz,
    demande_par     uuid,
    saisi_par       uuid
);
CREATE INDEX idx_examen_patient ON laboratoire.examen (patient_id, demande_le DESC);
CREATE INDEX idx_examen_statut ON laboratoire.examen (statut);

COMMENT ON TABLE laboratoire.examen IS
    'Examen de laboratoire (TDR palu en tête) — le diagnostic « paludisme » doit désormais s''appuyer sur un résultat ENREGISTRÉ (I6 corrigée : plus d''opinion non étayée).';

-- ---------------------------------------------------------------------
-- (6) STOCK — la dispensation décrémentée, la rupture est un 409
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS pharmacie;

CREATE TABLE pharmacie.stock_item (
    id              uuid PRIMARY KEY,
    structure_id    uuid NOT NULL,
    medication_code text NOT NULL,
    medication_label text NOT NULL,
    quantite        numeric(12,2) NOT NULL CHECK (quantite >= 0),
    seuil_alerte    integer NOT NULL DEFAULT 10,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (structure_id, medication_code)
);
CREATE INDEX idx_stock_structure ON pharmacie.stock_item (structure_id);

CREATE TABLE pharmacie.mouvement (
    id            uuid PRIMARY KEY,
    stock_item_id uuid NOT NULL REFERENCES pharmacie.stock_item(id),
    type          varchar(24) NOT NULL
                  CHECK (type IN ('reception','dispensation','contre_entree','ajustement')),
    quantite      numeric(12,2) NOT NULL CHECK (quantite > 0),
    reference_id  uuid,                      -- prescription concernée le cas échéant
    motif         text,
    created_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_mouvement_stock ON pharmacie.mouvement (stock_item_id, created_at DESC);

-- Append-only : aucun UPDATE, aucun DELETE (patron V3/V8, fonction locale
-- au schéma — jamais de SQL libre cross-schéma).
CREATE OR REPLACE FUNCTION pharmacie.interdit_reecriture() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'pharmacie.mouvement est append-only : la réécriture et la suppression sont interdites';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_mouvement_no_update
    BEFORE UPDATE OR DELETE ON pharmacie.mouvement
    FOR EACH ROW EXECUTE FUNCTION pharmacie.interdit_reecriture();

COMMENT ON TABLE pharmacie.stock_item IS
    'Stock par structure (COCOM) — la dispensation décrémente via pharmacie.mouvement. Une ligne de stock épuisée refuse la dispensation suivante (409 rupture — I8 corrigée). Sans ligne de stock, la dispensation passe avec audit (couverture progressive des référentiels).';

-- ---------------------------------------------------------------------
-- (7) RÉFÉRENCE / CONTRE-RÉFÉRENCE — la pyramide sanitaire tracée
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS reference;

CREATE TABLE reference.fiche (
    id                     uuid PRIMARY KEY,
    patient_id             uuid NOT NULL,
    structure_origine      uuid NOT NULL,
    structure_destination  uuid NOT NULL,
    motif                  text NOT NULL,
    urgence                boolean NOT NULL DEFAULT false,
    statut                 varchar(24) NOT NULL DEFAULT 'envoyee'
                           CHECK (statut IN ('envoyee','recue','hospitalisee','retournee','cloturee')),
    created_by             uuid,
    created_at             timestamptz NOT NULL DEFAULT now(),
    recue_le               timestamptz,
    contre_reference       text,             -- résumé de retour (obligatoire pour clore)
    contre_reference_le    timestamptz,
    contre_reference_par   uuid,
    client_request_id      uuid UNIQUE
);
CREATE INDEX idx_reference_patient ON reference.fiche (patient_id, created_at DESC);
CREATE INDEX idx_reference_origine ON reference.fiche (structure_origine, created_at DESC);
CREATE INDEX idx_reference_destination ON reference.fiche (structure_destination, statut);

COMMENT ON TABLE reference.fiche IS
    'Référence (CSPS→CMA/CHR/CHU) et contre-référence : la fiche numérique remplace les 3 volets papier. Une référence « envoyée » non reçue depuis 48 h est SIGNALÉE comme non aboutie dans les statistiques (I7 corrigée : la continuité des soins est tracée).';

-- ---------------------------------------------------------------------
-- (8) CAUSE DE DÉCÈS — les colonnes deceased/deceased_at existent (V1)
-- ---------------------------------------------------------------------

ALTER TABLE identity.patient
    ADD COLUMN IF NOT EXISTS cause_deces text;

COMMENT ON COLUMN identity.patient.cause_deces IS
    'Cause du décès (déclarée par un soignant, motif traçé en audit) — alimente le rapport de mortalité SNIS (I15 : le dossier d''un patient décédé est scellé, plus aucune consultation ni RDV).';

-- ---------------------------------------------------------------------
-- Droits applicatifs
-- ---------------------------------------------------------------------

GRANT USAGE ON SCHEMA rendezvous, laboratoire, pharmacie, reference TO app_rw;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA rendezvous TO app_rw;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA laboratoire TO app_rw;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA pharmacie TO app_rw;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA reference TO app_rw;
