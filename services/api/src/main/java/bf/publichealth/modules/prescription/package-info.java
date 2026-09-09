/**
 * Module prescription — prescriptions.
 *
 * <p>Prescriptions, catalogue médicaments, états de délivrance. Façade FHIR : MedicationRequest. La dispensation n'appartient pas à ce module (voir pharmacy) mais la prescription suit la délivrance par événements.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Transmission inter-structures via le connecteur HUB uniquement.</p>
 */
package bf.publichealth.modules.prescription;
