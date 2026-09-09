/**
 * Module notification — messages neutres.
 *
 * <p>Messages transactionnels neutres (Brevo — ADR-007), files d'attente, préférences. Adaptateur SMTP/SMTP-relais swap sans impact métier.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Jamais de donnée clinique dans une notification : titre, type, lien (HYPOTHÈSE À VALIDER : contenu exact autorisé).</p>
 */
package bf.publichealth.modules.notification;
