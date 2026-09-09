-- =====================================================================
-- V6 — Module identity (épique E1) : référence interne lisible + idempotence
--
-- PH-AAAA-NNNNNN : identifiant interne PUBLIC HEALTH, lisible à voix haute,
-- unique pour toujours. Le compteur est global (séquence PostgreSQL) ;
-- l'année est celle de la création (Burkina Faso = UTC+0). 6 chiffres
-- suffisent largement pour le pilote (~700k naissances/an au BF) ; le format
-- montera en digits sans casser l'unicité si le volume l'exige un jour.
--
-- client_request_id : idempotence de création offline-first (épique E2) —
-- un UUID généré par le client PWA, rejoué à la reprise réseau.
-- =====================================================================

CREATE SEQUENCE identity.patient_ref_seq START 1;

ALTER TABLE identity.patient
    ADD COLUMN ph_reference      varchar(16),
    ADD COLUMN client_request_id uuid;

-- Unicité : NULL permis (dossiers antérieurs à V6 / créations sans clé).
CREATE UNIQUE INDEX uniq_patient_ph_reference
    ON identity.patient (ph_reference);
CREATE UNIQUE INDEX uniq_patient_client_request
    ON identity.patient (client_request_id);

COMMENT ON COLUMN identity.patient.ph_reference IS
    'Identifiant interne lisible PH-AAAA-NNNNNN (D1 : PH interne, NUNP externe nullable)';
COMMENT ON COLUMN identity.patient.client_request_id IS
    'Clé idempotence de création (client offline-first) — rejeu = même dossier, jamais deux';

-- File de revue chronologique (FIFO) : l'ancienneté d'un rapprochement
-- en attente devient mesurable, ce que V1 ne permettait pas.
ALTER TABLE identity.identity_match
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
