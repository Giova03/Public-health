/**
 * Module encounter — contextes de soins.
 *
 * <p>Contextes de soins, états d'épisode, fenêtres d'accès aux données patients. Les UUID v7 sont générés côté client (ADR-05 offline-first) pour permettre la création hors ligne sans conflit de clé. Façade FHIR : Encounter.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). La référence patient_id est logique — jamais de clé étrangère inter-schémas (loi n°3).</p>
 */
package bf.publichealth.modules.encounter;
