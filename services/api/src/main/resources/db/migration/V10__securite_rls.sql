-- =====================================================================
-- V10 — Épique E5 : sécurité intégrale.
--
-- 1) audit.emergency_access : le registre du break-the-glass (accès
--    d'urgence, tracé et revu a posteriori — jamais bloquant).
--
-- 2) Rôle applicatif non-propriétaire app_rw (NOLOGIN) : octrois
--    SELECT/INSERT/UPDATE — JAMAIS DELETE (philosophie append-only).
--
-- 3) RLS intégrale « par utilisateur attribuable » : chaque table qui
--    porte une colonne d'attribution reçoit ENABLE + FORCE ROW LEVEL
--    SECURITY et des policies USING / WITH CHECK fondées sur l'identité
--    de session app.current_user_id() — c'est-à-dire exactement
--    current_setting('app.user_id', true) nettoyée des chaînes vides
--    (style V5, généralisé à tout le monolithe).
--
--    Contournement propriétaire (design imposé, prouvé par RlsAppRwIT) :
--    les super-utilisateurs et les rôles BYPASSRLS ne sont JAMAIS soumis
--    à la RLS, y compris FORCE. Les connexions de migration et de test
--    (Flyway sur « postgres » embarqué zonky, POSTGRES_USER de
--    Testcontainers en CI) sont super-utilisateurs : elles voient donc
--    TOUT, et les tests existants passent inchangés. Le rôle applicatif
--    app_rw, lui, est soumis aux policies : c'est le rôle de
--    l'application en staging/production. Un propriétaire de table NON
--    super-utilisateur serait en revanche soumis aux policies (sémantique
--    FORCE de PostgreSQL) : le modèle de déploiement imposé est donc
--    « Flyway = propriétaire (super-utilisateur ou BYPASSRLS) pour les
--    migrations, application = app_rw pour le trafic ».
--
-- AUCUNE colonne n'est ajoutée aux tables métier existentes.
--
-- Tables couvertes — colonne d'attribution inspectée :
--   identity.identity_match        reviewed_by       (revue de rapprochement)
--   identity.merge_log             performed_by      (fusion MPI)
--   payments.payment               initiated_by      (initiateur du paiement)
--   clinical.encounter             practitioner_id   (les policies facility de V5 restent actives en parallèle)
--   prescription.prescription      prescriber_id     (prescripteur)
--   prescription.dispensation      dispensed_by      (dispensateur)
--   sync.op                        user_id           (auteur de l'opération uplink)
--   sync.device                    user_id           (propriétaire de l'appareil)
--   audit.entry                    actor_id          (la policy admin V5 reste)
--   audit.emergency_access         user_id           (créée ci-dessous)
--
-- Tables SANS colonne attribuable — exclues de la RLS utilisateur en P0
-- (aucune colonne created_by/user_id/prescriber_id/dispensed_by...) :
--   identity.patient               MPI démographique : lecture nationale par design (RLS V5 patient_read, non forcée, inchangée)
--   identity.patient_name          démographie rattachée au patient
--   identity.patient_telecom       idem
--   identity.patient_address       idem
--   identity.patient_identifier    idem
--   payments.payment_transition    preuve comptable dérivée du paiement
--   payments.webhook_event         table machine (idempotence des webhooks)
--   clinical.observation           RLS facility V5 non forcée, inchangée (pas de colonne utilisateur)
--   clinical.condition             clinique P0 sans attribution utilisateur
--   clinical.medication_request    idem
--   clinical.allergy_intolerance   idem
--   prescription.prescription_item ligne rattachée à prescription.prescription
--   sync.outbox                    outbox transactionnel machine
--
-- Sémantique commune des policies (par table attribuable, colonne C) :
--   SELECT : app.current_user_id() = C  OU rôle admin (style V5)
--   INSERT : app.current_user_id() = C (échec silencieux sinon — fail-closed)
--   UPDATE : idem SELECT pour USING et WITH CHECK (revue a posteriori, annulation)
--   DELETE : AUCUNE policy — app_rw ne supprime jamais
-- Une ligne dont C est NULL n'appartient à personne : invisible pour
-- app_rw (fail-closed), lisible par le propriétaire/super-utilisateur.
-- =====================================================================

-- ---------------------------------------------------------------------
-- (1) Break-the-glass : registre des accès d'urgence
--
-- user_id NOT NULL : quand aucun contexte sécurité n'est actif (posture
-- Sprint 0, securite.jwt.actif=false), le service inscrit l'utilisateur
-- ANONYME 00000000-0000-0000-0000-000000000000 (patron V7 de sync.device,
-- « l'utilisateur anonyme » matérialisé) et la réponse API documente
-- userId=null. Les policies RLS ci-dessous ne matchent JAMAIS ce
-- sentinel : une brèche anonyme est invisible pour app_rw (fail-closed),
-- réservée au propriétaire/à l'admin — l'examen a posteriori prime.
-- ---------------------------------------------------------------------

CREATE TABLE audit.emergency_access (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL,
    patient_id     uuid NOT NULL,
    reason         text NOT NULL,
    opened_at      timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    reviewed       boolean NOT NULL DEFAULT false,
    review_comment text,
    reviewed_at    timestamptz
);

CREATE INDEX idx_emergency_access_patient ON audit.emergency_access (patient_id);
CREATE INDEX idx_emergency_access_pending ON audit.emergency_access (reviewed) WHERE NOT reviewed;

-- ---------------------------------------------------------------------
-- (2) Rôle applicatif — non propriétaire, soumis à la RLS
-- (octrois posés APRÈS la création de audit.emergency_access : la clause
-- ON ALL TABLES IN SCHEMA fige l'état courant des relations)
-- ---------------------------------------------------------------------

CREATE ROLE app_rw NOLOGIN;

GRANT USAGE ON SCHEMA identity, clinical, payments, prescription, sync, audit, app TO app_rw;

-- Trafic applicatif : lecture/écriture, jamais suppression.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA
    identity, clinical, payments, prescription, sync TO app_rw;

-- Schéma audit : append-only pour le journal (entry),
-- relecture/revue pour le break-the-glass (emergency_access).
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA audit TO app_rw;
GRANT UPDATE ON audit.emergency_access TO app_rw;

-- Séquences sous-jacentes (bigserial d'audit.entry et de
-- payments.payment_transition, référence PH d'identity).
GRANT USAGE ON ALL SEQUENCES IN SCHEMA
    identity, clinical, payments, prescription, sync, audit TO app_rw;

-- Les migrations futures (même rôle propriétaire) prolongent les octrois.
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, clinical, payments, prescription, sync
    GRANT SELECT, INSERT, UPDATE ON TABLES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, clinical, payments, prescription, sync
    GRANT USAGE ON SEQUENCES TO app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO app_rw;

-- ---------------------------------------------------------------------
-- (3) RLS intégrale — tables attribuables
-- ---------------------------------------------------------------------

-- identity.identity_match (reviewed_by) ------------------------------
ALTER TABLE identity.identity_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.identity_match FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_match_proprietaire_select ON identity.identity_match
    FOR SELECT
    USING (app.current_user_id() = reviewed_by OR app.has_role(ARRAY['admin']));
-- Revendication d'une rapprochement PENDING (reviewed_by NULL) : l'auteur
-- de la revue s'attribue la ligne ; ensuite, seul lui (ou un admin) y touche.
CREATE POLICY identity_match_proprietaire_update ON identity.identity_match
    FOR UPDATE
    USING (reviewed_by IS NULL OR app.current_user_id() = reviewed_by
           OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = reviewed_by OR app.has_role(ARRAY['admin']));

-- identity.merge_log (performed_by) — append-only ----------------------
ALTER TABLE identity.merge_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.merge_log FORCE ROW LEVEL SECURITY;
CREATE POLICY merge_log_proprietaire_select ON identity.merge_log
    FOR SELECT
    USING (app.current_user_id() = performed_by OR app.has_role(ARRAY['admin']));
CREATE POLICY merge_log_proprietaire_insert ON identity.merge_log
    FOR INSERT
    WITH CHECK (app.current_user_id() = performed_by);

-- payments.payment (initiated_by) --------------------------------------
ALTER TABLE payments.payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.payment FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_proprietaire_select ON payments.payment
    FOR SELECT
    USING (app.current_user_id() = initiated_by OR app.has_role(ARRAY['admin']));
CREATE POLICY payment_proprietaire_insert ON payments.payment
    FOR INSERT
    WITH CHECK (app.current_user_id() = initiated_by);
CREATE POLICY payment_proprietaire_update ON payments.payment
    FOR UPDATE
    USING (app.current_user_id() = initiated_by OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = initiated_by OR app.has_role(ARRAY['admin']));

-- clinical.encounter (practitioner_id) — les policies facility V5
-- (encounter_select) restent actives : la portée structure sanitaire et
-- la portée praticien se cumulent (policies permissives OR).
ALTER TABLE clinical.encounter ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical.encounter FORCE ROW LEVEL SECURITY;
CREATE POLICY encounter_proprietaire_select ON clinical.encounter
    FOR SELECT
    USING (app.current_user_id() = practitioner_id OR app.has_role(ARRAY['admin']));
CREATE POLICY encounter_proprietaire_insert ON clinical.encounter
    FOR INSERT
    WITH CHECK (app.current_user_id() = practitioner_id);
CREATE POLICY encounter_proprietaire_update ON clinical.encounter
    FOR UPDATE
    USING (app.current_user_id() = practitioner_id OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = practitioner_id OR app.has_role(ARRAY['admin']));

-- prescription.prescription (prescriber_id) — append-only, la garde
-- SQL V8 interdit déjà toute réécriture autre que la contre-entrée.
ALTER TABLE prescription.prescription ENABLE ROW LEVEL SECURITY;
ALTER TABLE prescription.prescription FORCE ROW LEVEL SECURITY;
CREATE POLICY prescription_proprietaire_select ON prescription.prescription
    FOR SELECT
    USING (app.current_user_id() = prescriber_id OR app.has_role(ARRAY['admin']));
CREATE POLICY prescription_proprietaire_insert ON prescription.prescription
    FOR INSERT
    WITH CHECK (app.current_user_id() = prescriber_id);
CREATE POLICY prescription_proprietaire_update ON prescription.prescription
    FOR UPDATE
    USING (app.current_user_id() = prescriber_id OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = prescriber_id OR app.has_role(ARRAY['admin']));

-- prescription.dispensation (dispensed_by) — append-only strict
-- (les gardes V8 interdisent UPDATE et DELETE).
ALTER TABLE prescription.dispensation ENABLE ROW LEVEL SECURITY;
ALTER TABLE prescription.dispensation FORCE ROW LEVEL SECURITY;
CREATE POLICY dispensation_proprietaire_select ON prescription.dispensation
    FOR SELECT
    USING (app.current_user_id() = dispensed_by OR app.has_role(ARRAY['admin']));
CREATE POLICY dispensation_proprietaire_insert ON prescription.dispensation
    FOR INSERT
    WITH CHECK (app.current_user_id() = dispensed_by);

-- sync.op (user_id) — l'idempotence de l'uplink, vue par son auteur.
ALTER TABLE sync.op ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync.op FORCE ROW LEVEL SECURITY;
CREATE POLICY sync_op_proprietaire_select ON sync.op
    FOR SELECT
    USING (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']));
CREATE POLICY sync_op_proprietaire_insert ON sync.op
    FOR INSERT
    WITH CHECK (app.current_user_id() = user_id);

-- sync.device (user_id) — la déclaration d'appareil et son curseur.
-- P0 : un appareil anonyme (user_id NULL, patron V7) ne peut être
-- déclaré que par le propriétaire/super-utilisateur — pas par app_rw
-- (fail-closed documenté : en production, l'appareil appartient à un
-- utilisateur authentifié).
ALTER TABLE sync.device ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync.device FORCE ROW LEVEL SECURITY;
CREATE POLICY sync_device_proprietaire_select ON sync.device
    FOR SELECT
    USING (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']));
CREATE POLICY sync_device_proprietaire_insert ON sync.device
    FOR INSERT
    WITH CHECK (app.current_user_id() = user_id);
CREATE POLICY sync_device_proprietaire_update ON sync.device
    FOR UPDATE
    USING (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']));

-- audit.entry (actor_id) — la policy admin V5 (audit_select) reste ;
-- chaque acteur relit SES propres entrées. L'insert reste libre
-- (audit_insert V5 WITH CHECK true) : l'audit ne doit jamais être
-- bloqué. FORCE : le journal ne se relit pas en silence.
ALTER TABLE audit.entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.entry FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_entry_proprietaire_select ON audit.entry
    FOR SELECT
    USING (app.current_user_id() = actor_id OR app.has_role(ARRAY['admin']));

-- audit.emergency_access (user_id) — la brèche est lue par son auteur
-- et par l'admin (examen a posteriori) ; la revue marque la ligne.
ALTER TABLE audit.emergency_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.emergency_access FORCE ROW LEVEL SECURITY;
CREATE POLICY emergency_access_proprietaire_select ON audit.emergency_access
    FOR SELECT
    USING (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']));
CREATE POLICY emergency_access_proprietaire_insert ON audit.emergency_access
    FOR INSERT
    WITH CHECK (app.current_user_id() = user_id);
CREATE POLICY emergency_access_proprietaire_update ON audit.emergency_access
    FOR UPDATE
    USING (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']))
    WITH CHECK (app.current_user_id() = user_id OR app.has_role(ARRAY['admin']));
