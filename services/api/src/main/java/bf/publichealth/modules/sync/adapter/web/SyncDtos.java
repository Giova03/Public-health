package bf.publichealth.modules.sync.adapter.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import bf.publichealth.modules.sync.application.MiroirPatients;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** DTO du module sync — immuables, contrats stables (loi n°5). */
public final class SyncDtos {

    private SyncDtos() {
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync/device
    // ------------------------------------------------------------------

    public record DeclarationAppareilRequest(
            UUID userId,
            @NotBlank @Size(max = 100) String deviceName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppareilResponse(UUID deviceId, String cursor) {
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync
    // ------------------------------------------------------------------

    public record RequeteUplink(
            UUID deviceId,
            UUID userId,
            @NotEmpty @Size(max = 500, message = "500 opérations maximum par lot")
            List<OpRequest> ops) {
    }

    /** Une op offline : opId = UUID v7 généré côté client (clé d'idempotence). */
    public record OpRequest(
            @NotNull UUID opId,
            @NotBlank @Size(max = 48) String entity,
            UUID entityId,
            UUID clientRequestId,
            UUID userId,
            JsonNode payload) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultatOpResponse(UUID opId, String result, JsonNode detail) {
    }

    public record ReponseUplink(List<ResultatOpResponse> results) {
    }

    // ------------------------------------------------------------------
    // GET /api/v1/sync/delta
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReponseDelta(String cursor, boolean hasMore, List<String> entities,
                               List<PatientMiroirResponse> patients) {
    }

    /** Ligne du miroir patient — le strict nécessaire pour IndexedDB. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatientMiroirResponse(
            UUID id, String phReference, boolean active, UUID masterId, String gender,
            LocalDate birthDate, boolean birthDateApproximative, long version,
            Instant updatedAt, List<NomResponse> names, List<TelecomResponse> telecoms) {

        public static PatientMiroirResponse from(MiroirPatients.PatientMiroir m) {
            return new PatientMiroirResponse(m.id(), m.phReference(), m.active(), m.masterId(),
                    m.gender(), m.birthDate(), m.birthDateApproximative(), m.version(), m.updatedAt(),
                    m.names().stream().map(NomResponse::from).toList(),
                    m.telecoms().stream().map(TelecomResponse::from).toList());
        }
    }

    public record NomResponse(String use, String family, String given) {

        public static NomResponse from(MiroirPatients.Nom n) {
            return new NomResponse(n.use(), n.family(), n.given());
        }
    }

    public record TelecomResponse(String system, String value, String use) {

        public static TelecomResponse from(MiroirPatients.Telecom t) {
            return new TelecomResponse(t.system(), t.value(), t.use());
        }
    }
}
