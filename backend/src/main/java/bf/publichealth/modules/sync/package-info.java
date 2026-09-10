/**
 * Module sync — protocole offline.
 *
 * <p>Protocole de synchronisation offline-first : idempotence par opId (sync.op, V4), curseurs par appareil (sync.device), outbox transactionnel (sync.outbox) écrit dans la même transaction que le fait métier. Trois régimes de conflit : clinique = sans conflit (append-only), métadonnées = dernier écrit gagne, identité = jamais fusionnée automatiquement.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma PostgreSQL ;
 * il n'est jamais joint directement par ses voisins — uniquement via des
 * interfaces ou des événements de domaine (ADR-001). Un opId ne s'applique qu'une fois, quoi qu'il arrive.</p>
 */
package bf.publichealth.modules.sync;
