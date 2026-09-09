package bf.publichealth.modules.fhir.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.util.Locale;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Timing;
import org.hl7.fhir.r4.model.Timing.TimingRepeatComponent;

/**
 * Conversions modèle interne → ressources FHIR R4 (le cœur de la façade E7).
 * Statiques et purs : aucune donnée n'est inventée, chaque écart de modèle
 * est un mapping DÉCISSIÉ et documenté ici — c'est le contrat des partenaires.
 *
 * <p>Règle générale RGPD santé : on ne rend QUE les éléments portés par les
 * schémas sources (identity V1/V6, clinical V3, prescription V8). Pas de
 * champ fabriqué, extensions minimales et documentées.</p>
 */
public final class FhirMappers {

    // ------------------------------------------------------------------
    // Systèmes d'identification (contrat de nommage PUBLIC HEALTH)
    // ------------------------------------------------------------------

    /** Identifiant interne lisible PH-AAAA-NNNNNN (V6). */
    public static final String SYSTEME_PH = "https://publichealth.bf/id/ph";
    /** NUNP — numéro unique national de personne (externe, nullable). */
    public static final String SYSTEME_NUNP = "https://publichealth.bf/id/nunp";
    /** CNIB — carte nationale d'identité burkinabè. */
    public static final String SYSTEME_CNIB = "https://publichealth.bf/id/cnib";
    /** Registres d'état civil antérieurs (code identity ANCIEN_REGISTRE). */
    public static final String SYSTEME_ANCIEN_REGISTRE = "https://publichealth.bf/id/ancien-registre";

    /**
     * CodeSystem local des codes d'observation — P0 SANS LOINC : le miroir
     * clinical.code est un code local texte (V3). Le passage LOINC (mapping
     * + crosswalk) est un chantier ultérieur ; le system local est stable et
     * documenté pour les partenaires.
     */
    public static final String SYSTEME_CODE_OBSERVATION =
            "https://publichealth.bf/fhir/CodeSystem/observation-code";

    /** CodeSystem local des médicaments (code + libellé du référentiel BF). */
    public static final String SYSTEME_MEDICATION =
            "https://publichealth.bf/fhir/CodeSystem/medication";

    private static final String SYSTEME_CLINICAL_STATUS =
            "http://terminology.hl7.org/CodeSystem/condition-clinical";
    private static final String SYSTEME_ACT_CODE =
            "http://terminology.hl7.org/CodeSystem/v3-ActCode";

    private FhirMappers() {
    }

    // ------------------------------------------------------------------
    // Patient (identity V1/V6 → R4 Patient)
    // ------------------------------------------------------------------

    public static Patient versPatient(LecturePatientsFhir.PatientFhir source) {
        Patient patient = new Patient();
        patient.setId(source.id().toString());
        patient.setActive(source.active());
        patient.getMeta().setLastUpdated(Date.from(source.updatedAt()));

        // identifier : ph_reference d'abord (l'identifiant MAITRE interne), puis
        // les identifiants nationaux. LOCAL est omis : bruit institutionnel,
        // non discriminant en interop (décision P0 documentée).
        if (source.phReference() != null) {
            patient.addIdentifier(new Identifier()
                    .setSystem(SYSTEME_PH).setValue(source.phReference()));
        }
        for (LecturePatientsFhir.IdentifiantFhir id : source.identifiants()) {
            String systeme = switch (id.system()) {
                case "NUNP" -> SYSTEME_NUNP;
                case "CNIB" -> SYSTEME_CNIB;
                case "ANCIEN_REGISTRE" -> SYSTEME_ANCIEN_REGISTRE;
                default -> null; // LOCAL : omis (voir ci-dessus)
            };
            if (systeme != null) {
                patient.addIdentifier(new Identifier().setSystem(systeme).setValue(id.value()));
            }
        }

        for (LecturePatientsFhir.NomFhir nom : source.noms()) {
            HumanName humanName = new HumanName();
            humanName.setUse("usual".equals(nom.use()) ? HumanName.NameUse.USUAL
                    : HumanName.NameUse.OFFICIAL);
            humanName.setFamily(nom.family());
            // given[] : le MPI stocke les prénoms en un texte — la sémantique
            // FHIR est une liste, on éclate sur les espaces (usage BF : prénoms
            // composés séparés par des espaces).
            for (String prenom : nom.given().trim().split("\\s+")) {
                if (!prenom.isBlank()) {
                    humanName.addGiven(prenom);
                }
            }
            patient.addName(humanName);
        }

        for (LecturePatientsFhir.TelecomFhir telecom : source.telecoms()) {
            ContactPoint point = new ContactPoint().setValue(telecom.value());
            point.setSystem("email".equals(telecom.system())
                    ? ContactPoint.ContactPointSystem.EMAIL
                    : ContactPoint.ContactPointSystem.PHONE);
            if (telecom.use() != null) {
                switch (telecom.use().toLowerCase(Locale.ROOT)) {
                    case "mobile" -> point.setUse(ContactPoint.ContactPointUse.MOBILE);
                    case "home" -> point.setUse(ContactPoint.ContactPointUse.HOME);
                    case "work" -> point.setUse(ContactPoint.ContactPointUse.WORK);
                    default -> { /* use libre côté MPI : on ne devine pas */ }
                }
            }
            patient.addTelecom(point);
        }

        // gender : les CHECK identity (V1) alignent déjà les codes R4 ;
        // NULL (genre non renseigné) → unknown, l'absence d'information
        // est une information (mapping exigé par la façade E7).
        String genre = source.gender() == null ? "unknown" : source.gender();
        try {
            patient.setGender(Enumerations.AdministrativeGender.fromCode(genre));
        } catch (IllegalArgumentException e) {
            patient.setGender(Enumerations.AdministrativeGender.UNKNOWN);
        }

        // birthDate : YYYY-MM-DD. Approximatif (birth_date_approximative) →
        // on DÉGRADE volontairement la précision à l'année seule (YYYY) :
        // l'exactitude affichée ne doit jamais dépasser celle connue —
        // le jour/mois d'une date approximative serait une invention.
        if (source.birthDate() != null) {
            patient.setBirthDateElement(source.birthDateApproximative()
                    ? new org.hl7.fhir.r4.model.DateType(String.valueOf(source.birthDate().getYear()))
                    : new org.hl7.fhir.r4.model.DateType(source.birthDate().toString()));
        }

        return patient;
    }

    // ------------------------------------------------------------------
    // Encounter (clinical V3 → R4 Encounter)
    // ------------------------------------------------------------------

    /**
     * Mapping Encounter.status (le schéma ne porte PAS de statut, il porte
     * des dates — on le DÉRIVE, décision documentée) :
     * <ul>
     *   <li>ended_at non null → {@code finished} ;</li>
     *   <li>started_at dans le futur → {@code planned} ;</li>
     *   <li>started_at atteint, pas de fin → {@code in-progress} ;</li>
     *   <li>cas impossible (started_at NOT NULL) → {@code unknown} défensif.</li>
     * </ul>
     */
    public static Encounter versEncounter(LectureCliniqueFhir.EncounterFhir source) {
        Encounter encounter = new Encounter();
        encounter.setId(source.id().toString());

        Instant maintenant = Instant.now();
        if (source.endedAt() != null) {
            encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        } else if (source.startedAt().isAfter(maintenant)) {
            encounter.setStatus(Encounter.EncounterStatus.PLANNED);
        } else {
            encounter.setStatus(Encounter.EncounterStatus.INPROGRESS);
        }

        // class : codes locaux → v3-ActCode. Classe locale non reconnue →
        // AMB (consultation externe) : le réel burkinabè (CSPS) est
        // majoritairement ambulatoire ; convention P0 documentée, jamais
        // une donnée inventée par ligne.
        encounter.setClass_(classeEncounter(source.encounterClass()));
        encounter.setSubject(referencePatient(source.patientId()));

        Period periode = new Period().setStart(Date.from(source.startedAt()));
        if (source.endedAt() != null) {
            periode.setEnd(Date.from(source.endedAt()));
        }
        encounter.setPeriod(periode);
        return encounter;
    }

    private static Coding classeEncounter(String classeLocale) {
        String cle = classeLocale == null ? "" : classeLocale.trim().toLowerCase(Locale.ROOT);
        return switch (cle) {
            case "urgence", "emergency" -> actCode("EMER", "emergency");
            case "hospitalisation", "inpatient", "hospitalise" -> actCode("IMP", "inpatient");
            case "domicile", "home", "home_health" -> actCode("HH", "home health");
            case "teleconsultation", "virtual" -> actCode("VR", "virtual");
            default -> actCode("AMB", "ambulatory"); // consultation/externe/unknown
        };
    }

    private static Coding actCode(String code, String display) {
        return new Coding().setSystem(SYSTEME_ACT_CODE).setCode(code).setDisplay(display);
    }

    // ------------------------------------------------------------------
    // Observation (clinical V3 → R4 Observation)
    // ------------------------------------------------------------------

    public static Observation versObservation(LectureCliniqueFhir.ObservationFhir source) {
        Observation observation = new Observation();
        observation.setId(source.id().toString());
        observation.setStatus("entered-in-error".equals(source.status())
                ? Observation.ObservationStatus.ENTEREDINERROR
                : Observation.ObservationStatus.FINAL);

        // code : coding SANS LOINC en P0 (system local documenté), code = code
        // local, display = le code lui-même (le miroir ne porte pas de libellé
        // séparé) + text miroir.
        observation.setCode(new CodeableConcept()
                .addCoding(new Coding().setSystem(SYSTEME_CODE_OBSERVATION)
                        .setCode(source.code()).setDisplay(source.code()))
                .setText(source.code()));

        observation.setSubject(referencePatient(source.patientId()));
        observation.setEffective(new DateTimeType(Date.from(source.effectiveAt())));

        // value : valueText SINON valueNum (le schéma offre les deux, une
        // observation n'porte qu'une valeur). Quantité sans unité : V3 n'en a
        // pas — on n'invente pas "/min" ni "°C".
        if (source.valueText() != null && !source.valueText().isBlank()) {
            observation.setValue(new StringType(source.valueText()));
        } else if (source.valueNum() != null) {
            observation.setValue(new Quantity().setValue(source.valueNum()));
        }
        return observation;
    }

    // ------------------------------------------------------------------
    // Condition (clinical V3 → R4 Condition)
    // ------------------------------------------------------------------

    /**
     * Mapping Condition.clinicalStatus — binding R4 REQUIRED sur
     * condition-clinical. Le schéma V3 n'a pas de CHECK : les valeurs connues
     * passent telles quelles (active, recurrence, relapse, inactive,
     * remission, resolved, problem-list-item) ; toute autre valeur (ou null)
     * → {@code inactive} : ne rien afficher d'actif par prudence clinique.
     */
    public static Condition versCondition(LectureCliniqueFhir.ConditionFhir source) {
        Condition condition = new Condition();
        condition.setId(source.id().toString());

        String statut = source.clinicalStatus() == null ? "" : source.clinicalStatus().toLowerCase(Locale.ROOT);
        String code = switch (statut) {
            case "active" -> "active";
            case "recurrence" -> "recurrence";
            case "relapse" -> "relapse";
            case "inactive" -> "inactive";
            case "remission" -> "remission";
            case "resolved" -> "resolved";
            case "problem-list-item" -> "problem-list-item";
            default -> "inactive";
        };
        condition.setClinicalStatus(new CodeableConcept().addCoding(
                new Coding().setSystem(SYSTEME_CLINICAL_STATUS).setCode(code)));

        // code : texte local libre en P0 (pas de système) — Condition.code est
        // un CodeableConcept, text seul est légal.
        condition.setCode(new CodeableConcept().setText(source.code()));
        condition.setSubject(referencePatient(source.patientId()));
        condition.setRecordedDate(Date.from(source.recordedAt()));
        return condition;
    }

    // ------------------------------------------------------------------
    // MedicationRequest (prescription V8 → R4 MedicationRequest, PAR LIGNE)
    // ------------------------------------------------------------------

    /**
     * Mapping MedicationRequest.status — PAR LIGNE de prescription (une
     * ressource = un médicament demandé) :
     * <ul>
     *   <li>prescription {@code cancelled} → {@code cancelled} (annulation
     *       logistique E3, motif obligatoire) ;</li>
     *   <li>prescription {@code entered-in-error} → {@code entered-in-error}
     *       (contre-entrée clinique E3) ;</li>
     *   <li>prescription {@code active} + ligne intégralement dispensée
     *       (cumul ≥ quantité prescrite) → {@code completed} ;</li>
     *   <li>sinon → {@code active}.</li>
     * </ul>
     * Le "completed" est donc DÉRIVÉ du cumul des dispensations (V8) —
     * c'est la seule sémantique honnête sur un état append-only.
     */
    public static MedicationRequest versMedicationRequest(
            LecturePrescriptionsFhir.PrescriptionFhir prescription,
            LecturePrescriptionsFhir.LigneFhir ligne) {
        MedicationRequest requete = new MedicationRequest();
        requete.setId(ligne.id().toString());

        if ("cancelled".equals(prescription.status())) {
            requete.setStatus(MedicationRequest.MedicationRequestStatus.CANCELLED);
        } else if ("entered-in-error".equals(prescription.status())) {
            requete.setStatus(MedicationRequest.MedicationRequestStatus.ENTEREDINERROR);
        } else {
            boolean epuisee = ligne.quantiteDispensee() != null
                    && ligne.quantityPrescribed() != null
                    && ligne.quantiteDispensee().compareTo(ligne.quantityPrescribed()) >= 0;
            requete.setStatus(epuisee
                    ? MedicationRequest.MedicationRequestStatus.COMPLETED
                    : MedicationRequest.MedicationRequestStatus.ACTIVE);
        }

        requete.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        requete.setMedication(new CodeableConcept()
                .addCoding(new Coding().setSystem(SYSTEME_MEDICATION)
                        .setCode(ligne.medicationCode())
                        .setDisplay(ligne.medicationLabel()))
                .setText(ligne.medicationLabel()));
        requete.setSubject(referencePatient(prescription.patientId()));
        requete.setAuthoredOnElement(new DateTimeType(Date.from(prescription.issuedAt())));

        // dosageInstruction : texte structuré simple assemblé des colonnes V8.
        Dosage dosage = new Dosage();
        dosage.setText(textePosologie(ligne));
        if (ligne.route() != null && !ligne.route().isBlank()) {
            dosage.setRoute(new CodeableConcept().setText(ligne.route()));
        }
        if (ligne.durationDays() != null && ligne.durationDays() > 0) {
            dosage.setTiming(new Timing().setRepeat(new TimingRepeatComponent()
                    .setDuration(BigDecimal.valueOf(ligne.durationDays()))
                    .setDurationUnit(Timing.UnitsOfTime.D)));
        }
        requete.addDosageInstruction(dosage);
        return requete;
    }

    /** Assemble « dose — forme — voie — fréquence — N jours » sans inventer. */
    private static String textePosologie(LecturePrescriptionsFhir.LigneFhir ligne) {
        StringBuilder texte = new StringBuilder();
        if (ligne.dose() != null && !ligne.dose().isBlank()) {
            texte.append(ligne.dose());
        }
        if (ligne.form() != null && !ligne.form().isBlank()) {
            texte.append(texte.isEmpty() ? "" : " — ").append(ligne.form());
        }
        if (ligne.route() != null && !ligne.route().isBlank()) {
            texte.append(texte.isEmpty() ? "" : " — ").append("voie ").append(ligne.route());
        }
        if (ligne.frequency() != null && !ligne.frequency().isBlank()) {
            texte.append(texte.isEmpty() ? "" : " — ").append(ligne.frequency());
        }
        if (ligne.durationDays() != null && ligne.durationDays() > 0) {
            texte.append(texte.isEmpty() ? "" : " — ")
                    .append(ligne.durationDays()).append(" jour(s)");
        }
        return texte.toString();
    }

    // ------------------------------------------------------------------
    // CapabilityStatement (contrat de la façade — reflète les routes réelles)
    // ------------------------------------------------------------------

    /**
     * Construit le CapabilityStatement — attention : il DOIT refléter
     * exactement ce que la façade livre (read + search-type, pas de
     * create/update/delete : LECTURE SEULE).
     */
    public static CapabilityStatement versCapabilityStatement(String versionLogiciel) {
        CapabilityStatement capacite = new CapabilityStatement();
        capacite.setUrl("https://publichealth.bf/fhir/CapabilityStatement/serveur");
        capacite.setStatus(Enumerations.PublicationStatus.ACTIVE);
        capacite.setDateElement(new DateTimeType(Instant.now().toString()));
        capacite.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        capacite.setSoftware(new CapabilityStatement.CapabilityStatementSoftwareComponent()
                .setName("PUBLIC HEALTH").setVersion(versionLogiciel));
        capacite.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        capacite.setFormat(List.of(new CodeType("json")));
        capacite.setImplementation(
                new CapabilityStatement.CapabilityStatementImplementationComponent()
                        .setDescription("Plateforme nationale de santé du Burkina Faso — "
                                + "façade FHIR R4 lecture seule (épique E7)"));

        CapabilityStatement.CapabilityStatementRestComponent rest =
                new CapabilityStatement.CapabilityStatementRestComponent();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.setDocumentation("""
                Façade FHIR R4 en LECTURE SEULE. Interactions : read et search-type \
                uniquement (aucune écriture — les partenaires écrivent via l'API \
                /api/v1). Pagination : _count (défaut 50, plafond 200 silencieux) et \
                offset page[offset] base 0, lien next dans le Bundle. Recherche \
                chaîne : préfixe insensible à la casse. Recherche token : valeur \
                exacte. Toutes les erreurs sont des OperationOutcome (404 not-found, \
                410 gone, 400 invalid). Format unique : application/fhir+json \
                (_format=json accepté).""");

        rest.addResource(ressource("Patient",
                """
                        Dossier patient du MPI (identity). Le 410 signale un dossier \
                        fusionné : suivre l'extension master-id vers le dossier maître. \
                        birthDate approximatif rendu en précision année. Extension \
                        master-id : https://publichealth.bf/fhir/StructureDefinition/master-id.""",
                parametre("family", "STRING", "Préfixe du patronyme (insensible à la casse)"),
                parametre("given", "STRING", "Préfixe du prénom (insensible à la casse)"),
                parametre("birthdate", "DATE", "Date de naissance exacte (YYYY-MM-DD, préfixe eq accepté)"),
                parametre("phone", "TOKEN", "Numéro de téléphone exact"),
                parametre("identifier", "TOKEN",
                        "Valeur seule (ph_reference ou identifiant national) ou system|value "
                                + "(system: " + SYSTEME_PH + ", " + SYSTEME_NUNP + ", "
                                + SYSTEME_CNIB + ")")));
        rest.addResource(ressource("Encounter",
                "Rencontres cliniques du miroir append-only (clinical). Statut dérivé des dates : "
                        + "finished (ended_at), planned (started_at futur), in-progress sinon.",
                parametre("patient", "REFERENCE", "Référence Patient (id interne uuid)")));
        rest.addResource(ressource("Observation",
                "Observations append-only (clinical). code = CodeSystem local "
                        + SYSTEME_CODE_OBSERVATION + " (LOINC absent en P0).",
                parametre("patient", "REFERENCE", "Référence Patient (id interne uuid)"),
                parametre("encounter", "REFERENCE", "Référence Encounter (id interne uuid)")));
        rest.addResource(ressource("Condition",
                "Problèmes de santé (clinical). clinicalStatus mappé sur condition-clinical ; "
                        + "valeur inconnue → inactive.",
                parametre("patient", "REFERENCE", "Référence Patient (id interne uuid)")));
        rest.addResource(ressource("MedicationRequest",
                "Prescriptions E3 : UNE ressource PAR LIGNE (id = prescription_item.id). "
                        + "Statut dérivé : cancelled / entered-in-error / completed (ligne "
                        + "intégralement dispensée) / active.",
                parametre("patient", "REFERENCE", "Référence Patient (id interne uuid)"),
                parametre("status", "TOKEN",
                        "Statut FHIR filtrant : active | completed | cancelled | entered-in-error")));

        capacite.setRest(List.of(rest));
        return capacite;
    }

    private static CapabilityStatement.CapabilityStatementRestResourceComponent ressource(
            String type, String documentation,
            CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent... params) {
        CapabilityStatement.CapabilityStatementRestResourceComponent r =
                new CapabilityStatement.CapabilityStatementRestResourceComponent();
        r.setType(type);
        r.setDocumentation(documentation);
        r.setInteraction(List.of(
                new CapabilityStatement.ResourceInteractionComponent()
                        .setCode(CapabilityStatement.TypeRestfulInteraction.READ),
                new CapabilityStatement.ResourceInteractionComponent()
                        .setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE)));
        r.setSearchParam(List.of(params));
        r.setReadHistory(false);
        r.setUpdateCreate(false);
        return r;
    }

    private static CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent parametre(
            String nom, String type, String documentation) {
        return new CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent()
                .setName(nom).setType(Enumerations.SearchParamType.valueOf(type))
                .setDocumentation(documentation);
    }

    // ------------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------------

    private static Reference referencePatient(UUID patientId) {
        return new Reference("Patient/" + patientId);
    }
}
