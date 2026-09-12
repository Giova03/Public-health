/**
 * Module fhir — LA frontière d'interopérabilité sortante (loi architecturale n°4).
 *
 * <p>Seuls ce module et le module hub ont le droit de parler FHIR : le reste
 * de la plateforme reste en modèle interne. La façade {@code /fhir/R4} est en
 * LECTURE SEULE (épique E7) — aucune écriture ne passe par elle ; les
 * partenaires interopérables lisent le MPI, le miroir clinique et le dossier
 * pharmacologique sous forme de ressources R4, jamais le modèle de stockage.</p>
 *
 * <p>Périmètre P0 : Patient (read + recherche), Encounter, Observation,
 * Condition, MedicationRequest (read + recherche par patient) et le
 * CapabilityStatement {@code /fhir/R4/metadata}. Toutes les erreurs sont des
 * {@code OperationOutcome} ; toutes les réponses sont
 * {@code application/fhir+json}.</p>
 *
 * <p>Lecture seule : les adaptateurs de persistance interrogent les schémas
 * identity / clinical / prescription par SELECT mono-schéma (patron
 * {@code MiroirPatientsPg} — aucun JOIN inter-schémas).</p>
 */
package bf.publichealth.modules.fhir;
