/**
 * Module consultation — observations cliniques.
 *
 * <p>Observations cliniques, motifs, comptes rendus, diagnostics. Append-only par conception : une erreur se corrige par contre-entrée explicite (status entered-in-error), jamais par réécriture — la garde SQL V3 l'impose. Façade FHIR : Observation, Condition.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Ce sont ces données qui rendent la synchronisation offline sans conflit.</p>
 */
package bf.publichealth.modules.consultation;
