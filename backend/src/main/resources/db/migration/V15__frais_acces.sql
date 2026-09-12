-- =====================================================================
-- V15 — FRAIS D'ACCÈS : LE PARCOURS MONÉTAIRE INVERSÉ (I5, P0.5-6).
--
-- Dans toute formation publique du Burkina Faso, on paie le ticket
-- modérateur AVANT d'être consulté (et l'exonération se décide à la
-- caisse) : admission -> caisse -> salle d'attente -> consultation.
-- La plateforme ne peut plus créer de consultation sans ticket réglé
-- du jour (payé ou exonéré) — la file réelle est enfin reproduite.
--
-- Contenu :
--  (1) payments.frais_acces — ticket d'accès par patient/structure/jour,
--      machine à états forward-only en_attente -> paye | exonere ;
--  (2) exonération TRACÉE : nature (indigent attesté, enfant < 5 ans,
--      césarienne, grossesse suivie), motif OBLIGATOIRE, décideur ;
--  (3) encaissement espèces tracé (caissier, montant XOF) — le ticket
--      du CSPS est un encaissement cash, pas une transaction FedaPay.
-- =====================================================================

-- ---------------------------------------------------------------------
-- (1) LE TICKET D'ACCÈS
-- ---------------------------------------------------------------------

CREATE TABLE payments.frais_acces (
    id              uuid PRIMARY KEY,
    patient_id      uuid NOT NULL,
    structure_id    uuid NOT NULL,
    statut          varchar(12) NOT NULL DEFAULT 'en_attente'
                    CHECK (statut IN ('en_attente','paye','exonere')),
    montant_xof     numeric(12,0) NOT NULL DEFAULT 1000
                    CHECK (montant_xof >= 0),
    -- Exonération (I5) : la caisse réelle du BF. NULL tant que le
    -- ticket n'est pas exonéré ; nature + motif + décideur imposés
    -- par le service au moment de la décision.
    exoneration_nature varchar(24)
                    CHECK (exoneration_nature IS NULL OR exoneration_nature IN
                    ('indigent_atteste','enfant_moins_5_ans',
                     'cesarienne','grossesse_suivie')),
    exoneration_motif text,
    exoneration_decidee_par uuid,
    encaisse_par    uuid,
    encaisse_le     timestamptz,
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Un ticket par patient, par structure, par JOUR (UTC) : la re-demande
-- du même jour renvoie le ticket existant (idempotence du parcours,
-- même patron que client_request_id V6 mais au pas du jour).
CREATE UNIQUE INDEX frais_acces_un_par_jour
    ON payments.frais_acces (patient_id, structure_id,
                             ((created_at AT TIME ZONE 'UTC')::date));

CREATE INDEX idx_frais_acces_structure_statut
    ON payments.frais_acces (structure_id, statut, created_at DESC);
CREATE INDEX idx_frais_acces_patient
    ON payments.frais_acces (patient_id, created_at DESC);

COMMENT ON TABLE payments.frais_acces IS
    'Ticket d''accès (I5) : encaissé ou exonéré À LA CAISSE avant la consultation. La création d''une consultation est REFUSÉE (402) tant que le ticket du jour n''est pas réglé — le parcours monétaire réel du BF, enfin dans l''ordre. Machine forward-only : en_attente -> paye ou exonere, jamais de retour arrière.';

COMMENT ON COLUMN payments.frais_acces.exoneration_nature IS
    'Nature de l''exonération décidée à la caisse (I5) : indigent attesté, enfant de moins de 5 ans, césarienne, grossesse suivie. La gratuité est la NORME pour ces catégories — le système la trace au lieu de l''ignorer.';

-- ---------------------------------------------------------------------
-- Droits applicatifs (patron V14)
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON payments.frais_acces TO app_rw;
