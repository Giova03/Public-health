-- =====================================================================
-- V11 — Épique E7 : index de lecture pour la façade FHIR R4.
--
-- LECTURE SEULE : aucune donnée modifiée — uniquement des index couvrant
-- les NOUVEAUX chemins d'accès introduits par la façade. V3 n'indexait
-- qu'encounter (patient_id) : observation et condition n'étaient lues
-- qu'identifiant par identifiant (uplink/offline, unicité par id). La
-- façade E7 y ajoute la RECHERCHE par patient/encounter — sans index,
-- chaque GET /fhir/R4/Observation?patient= serait un parcours séquentiel
-- complet du miroir clinique, rédhibitoire à l'échelle nationale.
--
-- patient_id + colonne de tri : les recherches de la façade ordonnent
-- par effective_at / recorded_at décroissant (les plus récents d'abord).
-- =====================================================================

CREATE INDEX idx_observation_patient
    ON clinical.observation (patient_id, effective_at DESC);

CREATE INDEX idx_observation_encounter
    ON clinical.observation (encounter_id);

CREATE INDEX idx_condition_patient
    ON clinical.condition (patient_id, recorded_at DESC);
