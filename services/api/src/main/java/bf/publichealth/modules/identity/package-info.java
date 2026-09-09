/**
 * Module identity — comptes et habilitations.
 *
 * <p>Comptes agents, habilitations RBAC × ABAC, annuaire des professionnels, sessions de poste. S'appuie sur Supabase Auth (ADR-007) et réserve la migration Keycloak. La préparation des variables de session app.* pour la RLS s'y branchera.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Jamais de donnée clinique ici.</p>
 */
package bf.publichealth.modules.identity;
