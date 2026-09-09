package bf.publichealth.modules.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import bf.publichealth.modules.fhir.application.FhirMappers;
import bf.publichealth.modules.fhir.application.FhirPagination;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.ConditionFhir;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.EncounterFhir;
import bf.publichealth.modules.fhir.application.LectureCliniqueFhir.ObservationFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.IdentifiantFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.NomFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.PatientFhir;
import bf.publichealth.modules.fhir.application.LecturePatientsFhir.TelecomFhir;
import bf.publichealth.modules.fhir.application.LecturePrescriptionsFhir.LigneFhir;
import bf.publichealth.modules.fhir.application.LecturePrescriptionsFhir.PrescriptionFhir;
import bf.publichealth.modules.fhir.domain.ParametreFhirInvalideException;

/**
 * Tests unitaires des conversions interne → R4 (le cœur de la façade E7) :
 * chaque décision de mapping est verrouillée ici, la conformité R4 étant
 * prouvée par FhirFacadeIT + FhirValidationTest.
 */
class FhirMappersTest {

    private static final Instant MAINTENANT = Instant.parse("2026-06-01T12:00:00Z");

    // ------------------------------------------------------------------
    // Patient
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Patient : naissance approximative rendue en précision ANNÉE, genre null → unknown")
    void patientNaissanceApproximativeEtGenreInconnu() {
        PatientFhir source = new PatientFhir(UUID.randomUUID(), "PH-2026-000123", true, null,
                null, LocalDate.of(1994, 6, 15), true,
                MAINTENANT, MAINTENANT,
                List.of(new NomFhir("official", "OUEDRAOGO", "Aminata")),
                List.of(new TelecomFhir("phone", "+22670112233", "mobile")),
                List.of(new IdentifiantFhir("NUNP", "NUNP-1"), new IdentifiantFhir("LOCAL", "CSPS-9")));

        Patient patient = FhirMappers.versPatient(source);

        // Précision dégradée : l'année seule, jamais un jour inventé.
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1994");
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.UNKNOWN);
        // LOCAL omis (bruit institutionnel) ; NUNP + ph_reference conservés.
        assertThat(patient.getIdentifier()).hasSize(2);
        assertThat(patient.getIdentifierFirstRep().getSystem())
                .isEqualTo("https://publichealth.bf/id/ph");
        assertThat(patient.getMeta().getLastUpdated()).isNotNull();
    }

    @Test
    @DisplayName("Patient : prénoms éclatés en liste given[], identifier ph en tête")
    void patientPrenomsEclatesEtPh() {
        PatientFhir source = new PatientFhir(UUID.randomUUID(), "PH-2026-000124", true, null,
                "male", LocalDate.of(1988, 3, 3), false,
                MAINTENANT, MAINTENANT,
                List.of(new NomFhir("official", "KABORE", "Issa Karim")),
                List.of(), List.of(new IdentifiantFhir("CNIB", "B123456789")));

        Patient patient = FhirMappers.versPatient(source);
        assertThat(patient.getNameFirstRep().getGiven())
                .extracting(StringType::getValue).containsExactly("Issa", "Karim");
        assertThat(patient.getIdentifier()).hasSize(2);
        assertThat(patient.getIdentifier().get(0).getSystem()).isEqualTo("https://publichealth.bf/id/ph");
        assertThat(patient.getIdentifier().get(1).getSystem()).isEqualTo("https://publichealth.bf/id/cnib");
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.MALE);
    }

    // ------------------------------------------------------------------
    // Encounter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Encounter : statut dérivé des dates (finished / planned / in-progress)")
    void encounterStatutDerive() {
        UUID patientId = UUID.randomUUID();
        assertThat(statut(new EncounterFhir(UUID.randomUUID(), patientId, "consultation", "fievre",
                Instant.parse("2025-01-01T08:00:00Z"), Instant.parse("2025-01-01T10:00:00Z"))))
                .isEqualTo(EncounterStatus.FINISHED);
        assertThat(statut(new EncounterFhir(UUID.randomUUID(), patientId, "consultation", null,
                Instant.parse("2099-01-01T08:00:00Z"), null)))
                .isEqualTo(EncounterStatus.PLANNED);
        assertThat(statut(new EncounterFhir(UUID.randomUUID(), patientId, "consultation", null,
                Instant.parse("2025-01-01T08:00:00Z"), null)))
                .isEqualTo(EncounterStatus.INPROGRESS);
    }

    @Test
    @DisplayName("Encounter : classes locales mappées v3-ActCode, inconnue → AMB documenté")
    void encounterClasses() {
        UUID patientId = UUID.randomUUID();
        assertThat(classe("consultation", patientId)).isEqualTo("AMB");
        assertThat(classe("urgence", patientId)).isEqualTo("EMER");
        assertThat(classe("hospitalisation", patientId)).isEqualTo("IMP");
        assertThat(classe("teleconsultation", patientId)).isEqualTo("VR");
        assertThat(classe("visite-mystere", patientId)).isEqualTo("AMB"); // convention P0
    }

    private EncounterStatus statut(EncounterFhir source) {
        return FhirMappers.versEncounter(source).getStatus();
    }

    private String classe(String classeLocale, UUID patientId) {
        Encounter encounter = FhirMappers.versEncounter(new EncounterFhir(
                UUID.randomUUID(), patientId, classeLocale, null,
                Instant.parse("2025-01-01T08:00:00Z"), Instant.parse("2025-01-01T10:00:00Z")));
        return encounter.getClass_().getCode();
    }

    // ------------------------------------------------------------------
    // Observation / Condition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Observation : valueText SINON valueNum, statut reporté tel quel")
    void observationValeursEtStatut() {
        UUID patientId = UUID.randomUUID();
        ObservationFhir numerique = new ObservationFhir(UUID.randomUUID(), UUID.randomUUID(),
                patientId, "TEMP_ER", null, new BigDecimal("38.9"), "final", MAINTENANT);
        ObservationFhir textuelle = new ObservationFhir(UUID.randomUUID(), UUID.randomUUID(),
                patientId, "SYMPT", "Toux seche", null, "entered-in-error", MAINTENANT);

        var observationNumerique = FhirMappers.versObservation(numerique);
        var observationTextuelle = FhirMappers.versObservation(textuelle);

        assertThat(observationNumerique.getValueQuantity().getValue().doubleValue()).isEqualTo(38.9);
        assertThat(observationNumerique.getValueQuantity().getUnit()).isNull();
        assertThat(observationNumerique.getStatus().toCode()).isEqualTo("final");
        assertThat(observationTextuelle.getValueStringType().getValue()).isEqualTo("Toux seche");
        assertThat(observationTextuelle.getStatus().toCode()).isEqualTo("entered-in-error");
    }

    @Test
    @DisplayName("Condition : clinicalStatus inconnu → inactive (prudence clinique, binding required)")
    void conditionStatutInconnu() {
        UUID patientId = UUID.randomUUID();
        var active = FhirMappers.versCondition(new ConditionFhir(UUID.randomUUID(),
                UUID.randomUUID(), patientId, "MALARIA", "active", MAINTENANT));
        var inconnu = FhirMappers.versCondition(new ConditionFhir(UUID.randomUUID(),
                UUID.randomUUID(), patientId, "XYZ", "statut-bizarre", MAINTENANT));
        var absent = FhirMappers.versCondition(new ConditionFhir(UUID.randomUUID(),
                UUID.randomUUID(), patientId, "XYZ", null, MAINTENANT));

        assertThat(active.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(inconnu.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("inactive");
        assertThat(absent.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("inactive");
        assertThat(inconnu.getCode().getText()).isEqualTo("XYZ");
    }

    // ------------------------------------------------------------------
    // MedicationRequest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MedicationRequest : completed si ligne épuisée, sinon active ; annulations reportées")
    void medicationRequestStatutsDerives() {
        UUID patientId = UUID.randomUUID();
        PrescriptionFhir active = prescription(patientId, "active");
        PrescriptionFhir annulee = prescription(patientId, "cancelled");
        PrescriptionFhir erreur = prescription(patientId, "entered-in-error");

        assertThat(statut(active, ligne("10.00", "10.00"))).isEqualTo("completed");
        assertThat(statut(active, ligne("10.00", "4.00"))).isEqualTo("active");
        assertThat(statut(active, ligne("10.00", "0"))).isEqualTo("active");
        assertThat(statut(annulee, ligne("10.00", "0"))).isEqualTo("cancelled");
        assertThat(statut(erreur, ligne("10.00", "10.00"))).isEqualTo("entered-in-error");
    }

    @Test
    @DisplayName("MedicationRequest : posologie structurée (texte assemblé + durée timing + voie)")
    void medicationRequestPosologie() {
        UUID patientId = UUID.randomUUID();
        MedicationRequest mr = FhirMappers.versMedicationRequest(prescription(patientId, "active"),
                new LigneFhir(UUID.randomUUID(), "PARA-500", "Paracétamol 500 mg",
                        "1 comprimé", "comprimé", "oral", "3 fois par jour", 5,
                        new BigDecimal("10.00"), BigDecimal.ZERO));

        assertThat(mr.getIntent().toCode()).isEqualTo("order");
        assertThat(mr.getMedicationCodeableConcept().getCodingFirstRep().getCode())
                .isEqualTo("PARA-500");
        assertThat(mr.getMedicationCodeableConcept().getText()).isEqualTo("Paracétamol 500 mg");
        assertThat(mr.getDosageInstructionFirstRep().getText())
                .contains("1 comprimé").contains("voie oral").contains("5 jour(s)");
        assertThat(mr.getDosageInstructionFirstRep().getRoute().getText()).isEqualTo("oral");
        assertThat(mr.getDosageInstructionFirstRep().getTiming().getRepeat().getDuration()
                .intValue()).isEqualTo(5);
        assertThat(mr.getSubject().getReference()).isEqualTo("Patient/" + patientId);
    }

    private PrescriptionFhir prescription(UUID patientId, String statut) {
        return new PrescriptionFhir(UUID.randomUUID(), patientId, null, UUID.randomUUID(),
                UUID.randomUUID(), statut, MAINTENANT, MAINTENANT, List.of());
    }

    private LigneFhir ligne(String prescrit, String dispense) {
        return new LigneFhir(UUID.randomUUID(), "PARA-500", "Paracétamol 500 mg",
                "1 comprimé", "comprimé", "oral", "3 fois par jour", 5,
                new BigDecimal(prescrit), new BigDecimal(dispense));
    }

    private String statut(PrescriptionFhir prescription, LigneFhir ligne) {
        return FhirMappers.versMedicationRequest(prescription, ligne).getStatus().toCode();
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pagination : défaut 50, plafond 200 silencieux, valeurs absurdes refusées")
    void paginationDefaultsEtBornes() {
        assertThat(FhirPagination.depuis(null, null)).isEqualTo(new FhirPagination(50, 0));
        assertThat(FhirPagination.depuis("10", "20")).isEqualTo(new FhirPagination(10, 20));
        assertThat(FhirPagination.depuis("9999", null).count()).isEqualTo(200);
        assertThatThrownBy(() -> FhirPagination.depuis("0", null))
                .isInstanceOf(ParametreFhirInvalideException.class);
        assertThatThrownBy(() -> FhirPagination.depuis("abc", null))
                .isInstanceOf(ParametreFhirInvalideException.class);
        assertThatThrownBy(() -> FhirPagination.depuis("10", "-1"))
                .isInstanceOf(ParametreFhirInvalideException.class);
        assertThatThrownBy(() -> FhirPagination.depuis("10", "pas-un-nombre"))
                .isInstanceOf(ParametreFhirInvalideException.class);
    }
}
