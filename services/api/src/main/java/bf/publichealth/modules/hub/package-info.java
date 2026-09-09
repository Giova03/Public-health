/**
 * Module hub — connecteur PH HUB.
 *
 * <p>Connecteur vers le PH HUB des partenaires : sorties HTTPS 443 uniquement (jamais d'entrée), file locale chiffrée, retrait avec trempe et gigue, enveloppe signée HMAC-SHA256 avec numéro de séquence et filigrane, détection de trous, file morte (DLQ). Partenaire = invité qui appelle chez lui.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Seuls fhir et hub ont le droit de parler « étranger » (loi n°4).</p>
 */
package bf.publichealth.modules.hub;
