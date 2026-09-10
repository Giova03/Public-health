package bf.publichealth.modules.payments.adapter.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import bf.publichealth.modules.payments.adapter.persistence.ReconciliationRunEntity;
import bf.publichealth.modules.payments.application.ReconciliationService;

/** DTO réconciliation — immuables, contrats stables (loi n°5). */
public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    /** Rapport complet d'un run : ce que renvoie POST /api/v1/reconciliation/run. */
    public record RunResponse(
            UUID id, Instant startedAt, Instant finishedAt, String triggeredBy,
            Map<String, Object> counts, Map<String, Object> detail) {

        public static RunResponse from(ReconciliationService.RapportRun rapport) {
            return new RunResponse(rapport.id(), rapport.startedAt(), rapport.finishedAt(),
                    rapport.triggeredBy(), rapport.counts(), rapport.detail());
        }

        /** Relecture d'un run persisté (jsonb → map). */
        public static RunResponse from(ReconciliationRunEntity run, ObjectMapper objectMapper) {
            return new RunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                    run.getTriggeredBy(), lectureJson(run.getCounts(), objectMapper),
                    lectureJson(run.getDetail(), objectMapper));
        }
    }

    private static Map<String, Object> lectureJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("Relecture jsonb impossible", e);
        }
    }
}
