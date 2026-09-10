/**
 * Module organization — structures sanitaires.
 *
 * <p>Établissements (CSPS, CMA, CHRU), services, affectations des soignants, tarifs de référence des frais d'accès. Source du dimensionnement facility_id utilisé par la RLS du module clinical.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Un établissement fermé est archivé, jamais supprimé.</p>
 */
package bf.publichealth.modules.organization;
