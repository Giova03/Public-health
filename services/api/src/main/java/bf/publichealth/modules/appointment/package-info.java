/**
 * Module appointment — rendez-vous.
 *
 * <p>Créneaux, rendez-vous, rappels. Façade FHIR prévue : Appointment, Schedule, Slot. Fonctionnalité post-P0 : modélisée, non construite.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Aucun contenu clinique nominatif.</p>
 */
package bf.publichealth.modules.appointment;
