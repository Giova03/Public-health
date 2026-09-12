package bf.publichealth.modules.pharmacie.adapter.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.pharmacie.adapter.persistence.StockJdbc;
import bf.publichealth.modules.pharmacie.application.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API stock — /api/v1/stock (V14, I8). Permission stock:gerer
 * (pharmacien, admin — le COCOM du CSPS via l'infirmier? non :
 * stock:gerer = pharmacien + admin ; l'infirmier CSPS est ajouté
 * si le déploiement le décide par la matrice, pas dans le code).
 *
 * <p>La rupture est UN ÉTAT VISIBLE (seuil, badge) et un REFUS
 * (409) — le module le mieux fini de l'UI décrivait un monde sans
 * pénurie ; désormais le stock existe, s'épuise et se commande.</p>
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** État du stock d'une structure + ruptures + mouvements récents. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> etat(
            @RequestParam UUID structureId,
            @RequestParam(required = false, defaultValue = "50") int limiteMouvements) {
        List<StockJdbc.StockItem> items = stockService.parStructure(structureId);
        List<StockJdbc.Mouvement> mouvements = stockService.mouvements(structureId, limiteMouvements);
        List<StockJdbc.StockItem> enRupture = items.stream()
                .filter(i -> i.quantite().signum() <= 0)
                .toList();
        List<StockJdbc.StockItem> sousSeuil = items.stream()
                .filter(i -> i.quantite().signum() > 0
                        && i.quantite().compareTo(BigDecimal.valueOf(i.seuilAlerte())) <= 0)
                .toList();
        return ResponseEntity.ok(Map.of(
                "structureId", structureId.toString(),
                "items", items.stream().map(StockResponse::de).toList(),
                "mouvements", mouvements.stream().map(MouvementResponse::de).toList(),
                "ruptures", enRupture.size(),
                "sousSeuil", sousSeuil.size()));
    }

    /** Réception de fourniture (CAMEG/CSD) ou ajustement d'inventaire. */
    @PostMapping("/mouvements")
    public ResponseEntity<?> mouvement(@Valid @RequestBody MouvementRequest requete,
                                       Authentication authentification) {
        try {
            StockJdbc.StockItem item = switch (requete.type()) {
                case "reception" -> stockService.reception(requete.structureId(),
                        requete.medicationCode(), requete.medicationLabel(),
                        requete.quantite(), requete.motif(), acteur(authentification));
                case "ajustement" -> stockService.inventaire(requete.structureId(),
                        requete.medicationCode(), requete.quantite(), requete.motif(),
                        acteur(authentification));
                default -> throw new IllegalArgumentException(
                        "Type de mouvement inconnu : " + requete.type()
                                + " (reception | ajustement)");
            };
            return ResponseEntity.ok(StockResponse.de(item));
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Mouvement refusé");
            return ResponseEntity.badRequest().body(problem);
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record MouvementRequest(
            @NotNull(message = "La structure est obligatoire") UUID structureId,
            @NotBlank(message = "Le code médicament est obligatoire") String medicationCode,
            String medicationLabel,
            @NotBlank(message = "Le type est obligatoire (reception | ajustement)") String type,
            @NotNull(message = "La quantité est obligatoire") BigDecimal quantite,
            String motif) {
    }

    public record StockResponse(UUID id, UUID structureId, String medicationCode,
                                String medicationLabel, BigDecimal quantite, int seuilAlerte,
                                boolean enRupture, boolean sousSeuil) {
        static StockResponse de(StockJdbc.StockItem item) {
            return new StockResponse(item.id(), item.structureId(), item.medicationCode(),
                    item.medicationLabel(), item.quantite(), item.seuilAlerte(),
                    item.quantite().signum() <= 0,
                    item.quantite().signum() > 0
                            && item.quantite().compareTo(BigDecimal.valueOf(item.seuilAlerte())) <= 0);
        }
    }

    public record MouvementResponse(UUID id, String medicationCode, String type,
                                    BigDecimal quantite, String motif, String date) {
        static MouvementResponse de(StockJdbc.Mouvement mouvement) {
            return new MouvementResponse(mouvement.id(), mouvement.medicationCode(),
                    mouvement.type(), mouvement.quantite(), mouvement.motif(),
                    mouvement.createdAt().toString());
        }
    }

    private static UUID acteur(Authentication authentification) {
        if (authentification instanceof JwtAuthenticationToken jeton
                && jeton.getToken().getSubject() != null) {
            try {
                return UUID.fromString(jeton.getToken().getSubject());
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return null;
    }
}
