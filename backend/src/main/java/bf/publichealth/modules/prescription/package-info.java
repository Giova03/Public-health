/**
 * Module prescription — prescriptions & dispensation (épique E3).
 *
 * <p>Prescriptions append-only (annulation par contre-entrée cancelled /
 * entered-in-error, jamais de réécriture ni suppression — mêmes gardes
 * SQL que le module clinical), lignes de médicaments, dispensations
 * partielles CUMULÉES jusqu'à épuisement du prescrit. Idempotence
 * offline par client_request_id (patron V6). Façade FHIR :
 * MedicationRequest + MedicationDispense.</p>
 *
 * <p>Décision d'architecture E3 : la dispensation vit dans CE module
 * (schéma prescription) car la règle de dispensation — le cumul ne peut
 * jamais excéder le prescrit — est indissociable de la prescription qui
 * la porte. Le module pharmacy reste réservé à l'épique future
 * pharmacie/stock (mouvements de stock, inventaires, seuils).</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma
 * PostgreSQL ; il n'est jamais joint directement par ses voisins —
 * uniquement via des interfaces ou des événements de domaine (ADR-001).
 * Ici : patient_id est une colonne uuid SANS FK inter-schémas, la
 * cohérence est assurée par l'application via le port
 * {@link bf.publichealth.modules.prescription.application.PatientLookup}
 * (adaptateur vers le module identity). Transmission inter-structures
 * via le connecteur HUB uniquement.</p>
 */
package bf.publichealth.modules.prescription;
