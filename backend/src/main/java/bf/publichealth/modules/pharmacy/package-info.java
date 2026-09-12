/**
 * Module pharmacy — dispensations.
 *
 * <p>Disponibilité déclarée des médicaments, réservations, dispensations. Les dispensations sont append-only : la preuve de délivrance ne se réécrit pas, elle s'annule par ligne compensatrice. Façade FHIR : MedicationDispense.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Le stock déclaré est une photographie honnête, jamais une promesse.</p>
 */
package bf.publichealth.modules.pharmacy;
