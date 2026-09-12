package bf.publichealth.modules.fhir.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Service;

import bf.publichealth.common.ContexteAppelant;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.ConditionFhir;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.EncounterFhir;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.ObservationFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.CriteresPatient;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.ResultatRecherche;
import bf.publichealth.modules.fhir.application.LecturePrescriptionsFhir.LigneFhir;
import bf.publichealth.modules.fhir.application.LecturePrescriptionsFhir.PrescriptionFhir;
import bf.publichealth.modules.fhir.domain.DossierFusionneException;
import bf.publichealth.modules.fhir.domain.ParametreFhirInvalideException;
import bf.publichealth.modules.fhir.domain.RessourceIntrouvableException;

/**
 * Orchestration de la façade FHIR lecture/recherche (épique E7) : parse les
 * paramètres, interroge les ports de lecture mono-schéma, convertit via
 * {@link FhirMappers}, emballe en Bundle via {@link FhirBundleFactory}.
 *
 * <p>Zéro écriture, zéro JOIN inter-schémas, zéro donnée inventée. Les
 * identifiants de recherche de type référence acceptent la forme brute
 * (uuid) ou {@code Patient/<uuid>} — convention FHIR.</p>
 *
 * <p>Suggestion 4 de l'audit de fidélité : « la façade FHIR n'audit
 * RIEN » — chaque lecture réussie (read ou search non vide) inscrit
 * désormais FHIR_READ / FHIR_SEARCH dans la chaîne d'audit append-only,
 * avec l'acteur du jeton. Qui a consulté quel dossier par la façade
 * interop est désormais traçable comme pour l'API MPI (PATIENT_READ).</p>
 */
@Service
public class FhirFacadeService {

    /**
     * Systems d'identifier acceptés en recherche token ({@code system|value}).
     * Les formes courtes (ph, nunp, cnib) sont tolérées pour l'ergonomie des
     * partenaires ; une URL de system inconnue rend un Bundle VIDE (sémantique
     * token FHIR : un system sans correspondance ne matche rien), pas une erreur.
     */
    private static final Map<String, String> SYSTEMS_TOKEN = Map.ofEntries(
            Map.entry(FhirMappers.SYSTEME_PH, "PH"),
            Map.entry("ph", "PH"),
            Map.entry(FhirMappers.SYSTEME_NUNP, "NUNP"),
            Map.entry("nunp", "NUNP"),
            Map.entry(FhirMappers.SYSTEME_CNIB, "CNIB"),
            Map.entry("cnib", "CNIB"),
            Map.entry(FhirMappers.SYSTEME_ANCIEN_REGISTRE, "ANCIEN_REGISTRE"),
            Map.entry("ancien-registre", "ANCIEN_REGISTRE"));

    private static final Set<String> STATUTS_MEDICATION_REQUEST =
            Set.of("active", "completed", "cancelled", "entered-in-error");

    private final LecturePatientsFhir patients;
    private final LectureCliniqueFhir clinique;
    private final LecturePrescriptionsFhir prescriptions;
    private final FhirBundleFactory bundles;
    private final AuditRecorder auditRecorder;

    public FhirFacadeService(LecturePatientsFhir patients, LectureCliniqueFhir clinique,
                             LecturePrescriptionsFhir prescriptions, FhirBundleFactory bundles,
                             AuditRecorder auditRecorder) {
        this.patients = patients;
        this.clinique = clinique;
        this.prescriptions = prescriptions;
        this.bundles = bundles;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Audit des lectures (suggestion 4) — FHIR_READ / FHIR_SEARCH
    // ------------------------------------------------------------------

    /** Lecture unitaire divulguée : ressource, identifiant, acteur du jeton. */
    private void tracerRead(String entite, UUID entityId) {
        auditRecorder.record(ContexteAppelant.acteur(), "FHIR_READ", entite, entityId,
                null, null, AuditEntryEntity.Result.SUCCESS, null);
    }

    /** Recherche non vide : le sujet (patient) + le nombre de ressources divulguées. */
    private void tracerSearch(String entite, UUID patientId, long resultats) {
        if (resultats <= 0) {
            return; // Bundle vide : aucune donnée divulguée, pas d'entrée.
        }
        auditRecorder.record(ContexteAppelant.acteur(), "FHIR_SEARCH", entite, patientId,
                null, null, AuditEntryEntity.Result.SUCCESS,
                Map.of("resultats", resultats));
    }

    // ------------------------------------------------------------------
    // Patient — read + recherche miroir
    // ------------------------------------------------------------------

    /** 404 si inconnu, 410 (master-id) si fusionné — sinon la ressource R4. */
    public Patient lirePatient(UUID id) {
        LecturePatientsFhir.PatientFhir source = patients.parId(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Patient", id));
        if (source.masterId() != null) {
            throw new DossierFusionneException(source.id(), source.masterId());
        }
        tracerRead("fhir_patient", id);
        return FhirMappers.versPatient(source);
    }

    public Bundle rechercherPatients(String family, String given, String phone,
                                     String birthdateBrut, String identifierBrut,
                                     FhirPagination page, String baseUrl) {
        Map<String, String> parametres = new LinkedHashMap<>();
        if (present(family)) {
            parametres.put("family", family.trim());
        }
        if (present(given)) {
            parametres.put("given", given.trim());
        }
        if (present(phone)) {
            parametres.put("phone", phone.trim());
        }
        LocalDate naissance = null;
        if (present(birthdateBrut)) {
            naissance = lireNaissance(birthdateBrut.trim());
            parametres.put("birthdate", naissance.toString());
        }
        String systemeIdentifiant = null;
        String valeurIdentifiant = null;
        if (present(identifierBrut)) {
            TokenIdentifiant token = lireIdentifiant(identifierBrut.trim());
            if (token.systemeInconnu()) {
                // System sans correspondance : aucune ressource ne peut matcher.
                return bundles.searchset(baseUrl, "/Patient", parametres, List.of(), 0, page);
            }
            systemeIdentifiant = token.systeme();
            valeurIdentifiant = token.valeur();
            parametres.put("identifier", identifierBrut.trim());
        }

        CriteresPatient criteres = new CriteresPatient(
                present(family) ? family.trim() : null,
                present(given) ? given.trim() : null,
                present(phone) ? phone.trim() : null,
                naissance, systemeIdentifiant, valeurIdentifiant);
        ResultatRecherche<LecturePatientsFhir.PatientFhir> resultat =
                patients.rechercher(criteres, page.count(), page.decalage());
        List<Patient> ressources = resultat.elements().stream()
                .map(FhirMappers::versPatient)
                .toList();
        tracerSearch("fhir_patient", null, resultat.total());
        return bundles.searchset(baseUrl, "/Patient", parametres, ressources,
                resultat.total(), page);
    }

    // ------------------------------------------------------------------
    // Encounter — read + recherche par patient
    // ------------------------------------------------------------------

    public Encounter lireEncounter(UUID id) {
        EncounterFhir source = clinique.encounterParId(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Encounter", id));
        tracerRead("fhir_encounter", id);
        return FhirMappers.versEncounter(source);
    }

    public Bundle rechercherEncounters(String patientBrut, FhirPagination page, String baseUrl) {
        UUID patientId = lireReference(patientBrut, "patient", "Encounter");
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put("patient", patientId.toString());
        ResultatRecherche<EncounterFhir> resultat =
                clinique.encountersParPatient(patientId, page.count(), page.decalage());
        List<Encounter> ressources = resultat.elements().stream()
                .map(FhirMappers::versEncounter)
                .toList();
        tracerSearch("fhir_encounter", patientId, resultat.total());
        return bundles.searchset(baseUrl, "/Encounter", parametres, ressources,
                resultat.total(), page);
    }

    // ------------------------------------------------------------------
    // Observation — read + recherche par patient et/ou encounter
    // ------------------------------------------------------------------

    public Observation lireObservation(UUID id) {
        ObservationFhir source = clinique.observationParId(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Observation", id));
        tracerRead("fhir_observation", id);
        return FhirMappers.versObservation(source);
    }

    public Bundle rechercherObservations(String patientBrut, String encounterBrut,
                                         FhirPagination page, String baseUrl) {
        if (!present(patientBrut) && !present(encounterBrut)) {
            throw new ParametreFhirInvalideException(
                    "Au moins un des paramètres patient ou encounter est requis : "
                            + "GET /fhir/R4/Observation?patient=<id>");
        }
        UUID patientId = present(patientBrut) ? lireReference(patientBrut, "patient", "Observation") : null;
        UUID encounterId = present(encounterBrut) ? lireReference(encounterBrut, "encounter", "Observation") : null;

        Map<String, String> parametres = new LinkedHashMap<>();
        if (patientId != null) {
            parametres.put("patient", patientId.toString());
        }
        if (encounterId != null) {
            parametres.put("encounter", encounterId.toString());
        }
        ResultatRecherche<ObservationFhir> resultat =
                clinique.observations(patientId, encounterId, page.count(), page.decalage());
        List<Observation> ressources = resultat.elements().stream()
                .map(FhirMappers::versObservation)
                .toList();
        tracerSearch("fhir_observation", patientId, resultat.total());
        return bundles.searchset(baseUrl, "/Observation", parametres, ressources,
                resultat.total(), page);
    }

    // ------------------------------------------------------------------
    // Condition — read + recherche par patient
    // ------------------------------------------------------------------

    public Condition lireCondition(UUID id) {
        ConditionFhir source = clinique.conditionParId(id)
                .orElseThrow(() -> new RessourceIntrouvableException("Condition", id));
        tracerRead("fhir_condition", id);
        return FhirMappers.versCondition(source);
    }

    public Bundle rechercherConditions(String patientBrut, FhirPagination page, String baseUrl) {
        UUID patientId = lireReference(patientBrut, "patient", "Condition");
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put("patient", patientId.toString());
        ResultatRecherche<ConditionFhir> resultat =
                clinique.conditionsParPatient(patientId, page.count(), page.decalage());
        List<Condition> ressources = resultat.elements().stream()
                .map(FhirMappers::versCondition)
                .toList();
        tracerSearch("fhir_condition", patientId, resultat.total());
        return bundles.searchset(baseUrl, "/Condition", parametres, ressources,
                resultat.total(), page);
    }

    // ------------------------------------------------------------------
    // MedicationRequest — read + recherche par patient (statut filtrable)
    // ------------------------------------------------------------------

    public MedicationRequest lireMedicationRequest(UUID ligneId) {
        LecturePrescriptionsFhir.LigneAvecPrescription source = prescriptions.ligneParId(ligneId)
                .orElseThrow(() -> new RessourceIntrouvableException("MedicationRequest", ligneId));
        tracerRead("fhir_medication_request", ligneId);
        return FhirMappers.versMedicationRequest(source.prescription(), source.ligne());
    }

    /**
     * Recherche MedicationRequest : UNE ressource par ligne de prescription.
     * Le filtre de statut porte sur le statut FHIR DÉRIVÉ (completed = ligne
     * intégralement dispensée). La pagination est faite en mémoire après
     * filtrage : volumétrie par patient faible (append-only, ordre de grandeur
     * pilote), le port ramène les prescriptions du patient en une requête.
     */
    public Bundle rechercherMedicationRequests(String patientBrut, String statutBrut,
                                               FhirPagination page, String baseUrl) {
        UUID patientId = lireReference(patientBrut, "patient", "MedicationRequest");
        String statut = null;
        if (present(statutBrut)) {
            statut = statutBrut.trim().toLowerCase(Locale.ROOT);
            if (!STATUTS_MEDICATION_REQUEST.contains(statut)) {
                throw new ParametreFhirInvalideException(
                        "status : active | completed | cancelled | entered-in-error (reçu : "
                                + statutBrut + ")");
            }
        }

        List<MedicationRequest> toutes = new ArrayList<>();
        for (PrescriptionFhir prescription : prescriptions.prescriptionsParPatient(patientId)) {
            for (LigneFhir ligne : prescription.lignes()) {
                toutes.add(FhirMappers.versMedicationRequest(prescription, ligne));
            }
        }
        final String statutFinal = statut;
        List<MedicationRequest> filtree = statut == null
                ? toutes
                : toutes.stream()
                        .filter(mr -> statutFinal.equals(mr.getStatus().toCode()))
                        .toList();

        int debut = Math.min(page.decalage(), filtree.size());
        int fin = Math.min(page.decalage() + page.count(), filtree.size());
        List<MedicationRequest> pageCourante = filtree.subList(debut, fin);

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put("patient", patientId.toString());
        if (statut != null) {
            parametres.put("status", statut);
        }
        tracerSearch("fhir_medication_request", patientId, filtree.size());
        return bundles.searchset(baseUrl, "/MedicationRequest", parametres, pageCourante,
                filtree.size(), page);
    }

    // ------------------------------------------------------------------
    // Internes — parsing FHIR des paramètres
    // ------------------------------------------------------------------

    private record TokenIdentifiant(String systeme, String valeur, boolean systemeInconnu) {
    }

    /**
     * Token identifier : {@code valeur}, {@code system|valeur} ou
     * {@code |valeur}. System inconnu → bundle vide (sémantique token),
     * valeurs absurdes → 400.
     */
    private static TokenIdentifiant lireIdentifiant(String brut) {
        int separateur = brut.indexOf('|');
        if (separateur < 0) {
            return new TokenIdentifiant(null, brut, false);
        }
        String systemeBrut = brut.substring(0, separateur).trim();
        String valeur = brut.substring(separateur + 1).trim();
        if (valeur.isEmpty()) {
            throw new ParametreFhirInvalideException(
                    "identifier : valeur requise après system| (reçu : " + brut + ")");
        }
        String systeme = SYSTEMS_TOKEN.get(systemeBrut);
        if (systeme == null) {
            systeme = SYSTEMS_TOKEN.get(systemeBrut.toLowerCase(Locale.ROOT));
        }
        return systeme == null
                ? new TokenIdentifiant(null, valeur, true)
                : new TokenIdentifiant(systeme, valeur, false);
    }

    /** birthdate : YYYY-MM-DD exact ; préfixe « eq » toléré ; sinon 400. */
    private static LocalDate lireNaissance(String brut) {
        String valeur = brut.startsWith("eq") ? brut.substring(2) : brut;
        try {
            return LocalDate.parse(valeur);
        } catch (DateTimeParseException e) {
            throw new ParametreFhirInvalideException(
                    "birthdate : date exacte attendue (YYYY-MM-DD, préfixe eq accepté) — reçu : " + brut);
        }
    }

    /** Référence de recherche : uuid nu ou Type/&lt;uuid&gt;. */
    private static UUID lireReference(String brut, String nom, String typeRessource) {
        if (!present(brut)) {
            throw new ParametreFhirInvalideException(
                    "Le paramètre " + nom + " est requis : GET /fhir/R4/" + typeRessource
                            + "?" + nom + "=<id>");
        }
        String valeur = brut.trim();
        int barre = valeur.indexOf('/');
        if (barre >= 0) {
            valeur = valeur.substring(barre + 1);
        }
        try {
            return UUID.fromString(valeur);
        } catch (IllegalArgumentException e) {
            throw new ParametreFhirInvalideException(
                    nom + " : identifiant uuid attendu (Patient/<uuid> ou <uuid>) — reçu : " + brut);
        }
    }

    private static boolean present(String valeur) {
        return valeur != null && !valeur.isBlank();
    }
}
