package bf.publichealth.modules.payments.adapter.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import bf.publichealth.modules.payments.adapter.persistence.ReconciliationRunRepository;
import bf.publichealth.modules.payments.application.ReconciliationService;
import org.springframework.data.domain.PageRequest;

/**
 * API réconciliation — /api/v1/reconciliation (épique E4).
 *
 * <p>Déclenchement MANUEL (tests + supervision) : POST /run exécute le
 * MÊME traitement que le job nocturne (triggered_by=manuel) et rend le
 * rapport complet. L'historique des runs : GET /runs?limit= (défaut 20,
 * maximum 100), le plus récent d'abord.</p>
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private static final int LIMITE_DEFAUT = 20;
    private static final int LIMITE_MAX = 100;

    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public ReconciliationController(ReconciliationService reconciliationService,
                                    ReconciliationRunRepository runRepository,
                                    ObjectMapper objectMapper) {
        this.reconciliationService = reconciliationService;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    /** Exécute un run manuel et rend le rapport (compteurs + détail). */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationDtos.RunResponse> executer() {
        var rapport = reconciliationService.executer("manuel");
        return ResponseEntity.ok(ReconciliationDtos.RunResponse.from(rapport));
    }

    /** Historique des runs, le plus récent d'abord. */
    @GetMapping("/runs")
    public ResponseEntity<List<ReconciliationDtos.RunResponse>> historique(
            @RequestParam(required = false) Integer limit) {
        int effective = limit == null ? LIMITE_DEFAUT : limit;
        if (effective < 1 || effective > LIMITE_MAX) {
            throw new IllegalArgumentException(
                    "limit doit être entre 1 et %d".formatted(LIMITE_MAX));
        }
        List<ReconciliationDtos.RunResponse> runs = runRepository
                .findByOrderByStartedAtDesc(PageRequest.of(0, effective))
                .stream()
                .map(run -> ReconciliationDtos.RunResponse.from(run, objectMapper))
                .toList();
        return ResponseEntity.ok(runs);
    }
}
