/**
 * Module patient — MPI national.
 *
 * <p>Le dossier d'or démographique : identifiants nationaux (NUNP externe nullable — D1, CNIB), rapprochements probabilistes, file de revue, fusions irréversibles toujours tracées. Le schéma identity est livré par V1__identity_mpi.sql.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Contrat 409 = contrat UX : le doublon probable remonte à l'utilisateur, jamais fusionné en silence.</p>
 */
package bf.publichealth.modules.patient;
