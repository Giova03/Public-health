package bf.publichealth.modules.hub.adapter.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.hub.application.HubService;
import jakarta.validation.Valid;

/**
 * API du connecteur PH HUB — /api/v1/hub/** (sous /api/v1/**, posture
 * Sprint 0 : aucune modification de SecurityConfig).
 *
 * <p>Routes :
 * <ul>
 *   <li>{@code POST /api/v1/hub/drain} — drainage manuel (tests,
 *       supervision) : rapport {messages_crees, envoyes, acquittes,
 *       morts, watermark_par_destination, trous_detectes} ;</li>
 *   <li>{@code GET /api/v1/hub/messages?status=&destination=&limit=} —
 *       file, récents d'abord (défaut 50) ;</li>
 *   <li>{@code GET /api/v1/hub/destinations} — destinations + stats ;</li>
 *   <li>{@code POST /api/v1/hub/destinations} — création (le secret
 *       n'est JAMAIS rendu) ;</li>
 *   <li>{@code POST /api/v1/hub/messages/{id}/retry} — relance manuelle
 *       d'un message mort.</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1/hub")
public class HubController {

    /** Limite par défaut de la liste des messages. */
    static final int LIMITE_DEFAUT = 50;

    /** Limite maximale admise (la supervision reste légère). */
    static final int LIMITE_MAX = 200;

    private final HubService hubService;

    public HubController(HubService hubService) {
        this.hubService = hubService;
    }

    // ------------------------------------------------------------------
    // POST /api/v1/hub/drain — drainage manuel (tests + supervision)
    // ------------------------------------------------------------------

    @PostMapping("/drain")
    public ResponseEntity<HubDtos.RapportDrainResponse> drain() {
        return ResponseEntity.ok(HubDtos.RapportDrainResponse.from(hubService.drainManuel()));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/hub/messages — la file, récents d'abord
    // ------------------------------------------------------------------

    @GetMapping("/messages")
    public ResponseEntity<HubDtos.ReponseMessages> messages(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) Integer limit) {
        int limite = limite(limit);
        List<HubDtos.MessageResponse> messages = hubService.messages(status, destination, limite)
                .stream().map(HubDtos.MessageResponse::from).toList();
        return ResponseEntity.ok(new HubDtos.ReponseMessages(messages));
    }

    // ------------------------------------------------------------------
    // GET/POST /api/v1/hub/destinations — supervision et création
    // ------------------------------------------------------------------

    @GetMapping("/destinations")
    public ResponseEntity<List<HubDtos.DestinationAvecStatsResponse>> destinations() {
        return ResponseEntity.ok(hubService.destinationsAvecStats().stream()
                .map(HubDtos.DestinationAvecStatsResponse::from).toList());
    }

    @PostMapping("/destinations")
    public ResponseEntity<HubDtos.DestinationResponse> creerDestination(
            @Valid @RequestBody HubDtos.CreerDestinationRequest request) {
        var destination = hubService.creerDestination(request.code(), request.base_url(),
                request.actif(), request.secret());
        // 201 — la réponse ne contient JAMAIS le secret.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(HubDtos.DestinationResponse.from(destination));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/hub/messages/{id}/retry — relance d'un message mort
    // ------------------------------------------------------------------

    @PostMapping("/messages/{id}/retry")
    public ResponseEntity<HubDtos.MessageResponse> retry(@PathVariable UUID id) {
        return ResponseEntity.ok(HubDtos.MessageResponse.from(hubService.relancer(id)));
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private static int limite(Integer limite) {
        if (limite == null) {
            return LIMITE_DEFAUT;
        }
        if (limite < 1 || limite > LIMITE_MAX) {
            throw new IllegalArgumentException(
                    "limit doit être entre 1 et " + LIMITE_MAX + " : " + limite);
        }
        return limite;
    }
}
