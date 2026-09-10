/**
 * Module hub — connecteur PH HUB (épique E3, fin).
 *
 * <p>Connecteur UNIQUEMENT sortant vers le PH HUB des partenaires
 * (HTTPS 443, jamais d'entrée), déployé localement. Le hub est le
 * draineur officiel du transactional outbox {@code sync.outbox} (porte
 * de migration Kafka annoncée) : chaque événement non publié devient,
 * pour chaque destination active, une enveloppe canonique signée
 * HMAC-SHA256 (les octets exacts portent la signature) munie d'une
 * séquence monotone PAR destination ; l'acquittement fait avancer le
 * filigrane (watermark, strictement monotone), les échecs transitoires
 * retransmettent avec trempe exponentielle et gigue, la lettre morte
 * (DLQ) intervient après rejet définitif ou épuisement des tentatives
 * — auditée DENIED et relançable à la main. Les trous de séquence
 * (acquittement au-delà d'une séquence jamais confirmée) sont criés en
 * ERROR et rendus dans le rapport de drainage.</p>
 *
 * <p>Frontière d'interopérabilité inter-plateformes du Burkina : seul
 * avec fhir à parler « l'étranger » (loi architecturale n°4). Le secret
 * HMAC ne vit ni en base, ni dans application.yml, ni dans les journaux,
 * ni dans les réponses API — défauts de développement dans le code
 * (destination ph-hub-simulation), production par la propriété
 * {@code hub.secret-destinations}.</p>
 *
 * <p>Règles structurelles : un module = un périmètre + son schéma
 * PostgreSQL (V13) ; il n'est jamais joint directement par ses voisins
 * — uniquement via des interfaces ou des événements de domaine
 * (ADR-001). L'accès mono-schema à {@code sync.outbox} est le patron
 * accepté MiroirPatientsPg (lecture + marquage published, MÊME
 * transaction locale que la création des messages).</p>
 */
package bf.publichealth.modules.hub;
