package bf.publichealth.modules.identity.adapter.web;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import bf.publichealth.modules.identity.adapter.persistence.IdentityMatchEntity;
import bf.publichealth.modules.identity.adapter.persistence.MergeLogEntity;
import bf.publichealth.modules.identity.application.PatientService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTO du module identity — immuables, contrats stables (loi n°5). */
public final class PatientDtos {

    private PatientDtos() {
    }

    public record NameInput(
            @Pattern(regexp = "official|usual", message = "use : official ou usual")
            String use,
            @NotBlank @Size(max = 100) String family,
            @NotBlank @Size(max = 100) String given) {
    }

    public record TelecomInput(
            @Pattern(regexp = "phone|email", message = "system : phone ou email")
            String system,
            @NotBlank @Size(max = 64) String value,
            String use) {
    }

    public record IdentifierInput(
            @Pattern(regexp = "NUNP|CNIB|ANCIEN_REGISTRE|LOCAL",
                     message = "system : NUNP, CNIB, ANCIEN_REGISTRE ou LOCAL")
            String system,
            @NotBlank @Size(max = 64) String value) {
    }

    public record CreatePatientRequest(
            UUID clientRequestId,
            boolean forceCreate,
            UUID duplicateOfRejected,
            @Pattern(regexp = "male|female|other|unknown",
                     message = "gender : male, female, other ou unknown")
            String gender,
            @Past LocalDate birthDate,
            Boolean birthDateApproximative,
            @NotEmpty List<NameInput> names,
            List<TelecomInput> telecoms,
            List<IdentifierInput> identifiers,
            UUID createdBy) {
    }

    public record NameResponse(String use, String family, String given) {
    }

    public record TelecomResponse(String system, String value, String use) {
    }

    public record IdentifierResponse(String system, String value) {
    }

    public record PatientResponse(
            UUID id, String phReference, boolean active, String gender,
            LocalDate birthDate, boolean birthDateApproximative, UUID masterId,
            long version, List<NameResponse> names, List<TelecomResponse> telecoms,
            List<IdentifierResponse> identifiers, Instant createdAt) {

        public static PatientResponse from(PatientService.PatientAggregate dossier) {
            var p = dossier.patient();
            return new PatientResponse(p.getId(), p.getPhReference(), p.getActive(),
                    p.getGender(), p.getBirthDate(), p.isBirthDateApproximative(),
                    p.getMasterId(), p.getVersion(),
                    dossier.names().stream()
                            .map(n -> new NameResponse(n.getUse(), n.getFamily(), n.getGiven()))
                            .toList(),
                    dossier.telecoms().stream()
                            .map(t -> new TelecomResponse(t.getSystem(), t.getValue(), t.getUse()))
                            .toList(),
                    dossier.identifiers().stream()
                            .map(i -> new IdentifierResponse(i.getSystem(), i.getValue()))
                            .toList(),
                    p.getCreatedAt());
        }
    }

    public record MatchResponse(UUID id, UUID candidateA, UUID candidateB,
                                double score, String method, String status,
                                UUID reviewedBy, String motif, Instant createdAt) {

        public static MatchResponse from(IdentityMatchEntity m) {
            return new MatchResponse(m.getId(), m.getCandidateA(), m.getCandidateB(),
                    m.getScore(), m.getMethod(), m.getStatus(), m.getReviewedBy(),
                    m.getMotif(), m.getCreatedAt());
        }
    }

    public record CreateMatchRequest(
            @NotNull UUID candidateA,
            @NotNull UUID candidateB,
            String method) {
    }

    public record ReviewRequest(
            @Pattern(regexp = "ACCEPTED|REJECTED", message = "decision : ACCEPTED ou REJECTED")
            @NotBlank String decision,
            UUID masterId,
            @NotBlank @Size(max = 500) String motif,
            UUID reviewedBy) {
    }

    public record MergeLogResponse(UUID id, UUID masterId, UUID mergedId,
                                   String reason, UUID performedBy, Instant performedAt) {

        public static MergeLogResponse from(MergeLogEntity m) {
            return new MergeLogResponse(m.getId(), m.getMasterId(), m.getMergedId(),
                    m.getReason(), m.getPerformedBy(), m.getPerformedAt());
        }
    }
}
