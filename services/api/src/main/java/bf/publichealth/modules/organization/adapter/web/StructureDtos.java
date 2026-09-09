package bf.publichealth.modules.organization.adapter.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bf.publichealth.modules.organization.adapter.persistence.StructureEntity;
import bf.publichealth.modules.organization.application.StructureService;

/** DTO du module organization — immuables, contrats stables (loi n°5). */
public final class StructureDtos {

    private StructureDtos() {
    }

    // ------------------------------------------------------------------
    // Requêtes
    // ------------------------------------------------------------------

    public record CreateStructureRequest(
            @NotBlank(message = "La mnémonique officielle (code) est OBLIGATOIRE")
            @Pattern(regexp = "[A-Za-z0-9-]{3,32}",
                     message = "Le code est une mnémonique : 3 à 32 caractères alphanumériques et tirets (ex. CSPS-OUA-014)")
            String code,
            @NotBlank(message = "Le nom de la structure est OBLIGATOIRE")
            @Size(max = 200) String nom,
            @NotNull(message = "Le type de structure est OBLIGATOIRE")
            @Pattern(regexp = "csp|cs|cm|chu|chup|cma|private|pharmacy",
                     message = "type doit valoir csp, cs, cm, chu, chup, cma, private ou pharmacy")
            String type,
            @Size(max = 100) String region,
            @Size(max = 100) String province,
            @Size(max = 100) String commune,
            @DecimalMin(value = "-90", message = "latitude : -90 à 90")
            @DecimalMax(value = "90", message = "latitude : -90 à 90")
            @Digits(integer = 2, fraction = 6) BigDecimal latitude,
            @DecimalMin(value = "-180", message = "longitude : -180 à 180")
            @DecimalMax(value = "180", message = "longitude : -180 à 180")
            @Digits(integer = 3, fraction = 6) BigDecimal longitude) {
    }

    /**
     * PATCH — fusion : seuls les champs RENSEIGNÉS changent (null =
     * inchangé, limitation P0 : impossible de vider un champ via ce
     * contrat). L'activation ne passe PAS ici (activate/deactivate).
     */
    public record UpdateStructureRequest(
            @Size(min = 1, max = 200) String nom,
            @Pattern(regexp = "csp|cs|cm|chu|chup|cma|private|pharmacy",
                     message = "type doit valoir csp, cs, cm, chu, chup, cma, private ou pharmacy")
            String type,
            @Size(max = 100) String region,
            @Size(max = 100) String province,
            @Size(max = 100) String commune,
            @DecimalMin(value = "-90", message = "latitude : -90 à 90")
            @DecimalMax(value = "90", message = "latitude : -90 à 90")
            @Digits(integer = 2, fraction = 6) BigDecimal latitude,
            @DecimalMin(value = "-180", message = "longitude : -180 à 180")
            @DecimalMax(value = "180", message = "longitude : -180 à 180")
            @Digits(integer = 3, fraction = 6) BigDecimal longitude) {
    }

    // ------------------------------------------------------------------
    // Réponses
    // ------------------------------------------------------------------

    public record StructureResponse(
            UUID id, String code, String nom, String type,
            String region, String province, String commune,
            BigDecimal latitude, BigDecimal longitude, boolean active,
            Instant createdAt, Instant updatedAt) {

        public static StructureResponse from(StructureEntity s) {
            return new StructureResponse(s.getId(), s.getCode(), s.getNom(),
                    s.getType().getCode(), s.getRegion(), s.getProvince(), s.getCommune(),
                    s.getLatitude(), s.getLongitude(), s.isActive(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    /** Ligne d'arborescence : la couverture sanitaire d'une région. */
    public record ArborescenceResponse(
            String region, long total, long actives, Map<String, Long> parType) {

        public static ArborescenceResponse from(StructureService.ArborescenceRegion a) {
            return new ArborescenceResponse(a.region(), a.total(), a.actives(), a.parType());
        }
    }

    public static List<StructureResponse> liste(List<StructureEntity> structures) {
        return structures.stream().map(StructureResponse::from).toList();
    }
}
