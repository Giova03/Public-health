/**
 * Module administration — référentiels et configuration.
 *
 * <p>Référentiels transverses (nomenclatures SNIMI, unités, géographie administrative), configuration fonctionnelle, gestion des environnements. Back-office minimal : utilisateurs, structures, rôles.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). MFA obligatoire pour tout compte administrateur.</p>
 */
package bf.publichealth.modules.administration;
