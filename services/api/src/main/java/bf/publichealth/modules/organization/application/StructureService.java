package bf.publichealth.modules.organization.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.organization.adapter.persistence.StructureEntity;
import bf.publichealth.modules.organization.adapter.persistence.StructureRepository;
import bf.publichealth.modules.organization.domain.CodeStructureDejaUtiliseException;
import bf.publichealth.modules.organization.domain.IllegalStructureTransitionException;
import bf.publichealth.modules.organization.domain.StructureIntrouvableException;
import bf.publichealth.modules.organization.domain.TypeStructure;

/**
 * Cas d'usage annuaire des structures sanitaires — épique E6.
 *
 * <p>Une structure ne se supprime JAMAIS : elle se désactive (soft,
 * {@code active=false}) et se réactive — chaque transition est refusée
 * en 409 si l'état demandé est déjà en place, et tracée en audit
 * (patron payments : six dimensions, chaînage, REQUIRES_NEW dans
 * l'AuditRecorder). La mise à jour porte le nom, le type et la
 * localisation ; l'activation est gérée séparément. La géolocalisation
 * est optionnelle mais va par PAIRE (latitude sans longitude = erreur
 * de saisie, on refuse plutôt que d'inventer).</p>
 */
@Service
public class StructureService {

    private static final Logger LOG = LoggerFactory.getLogger(StructureService.class);

    // ------------------------------------------------------------------
    // Entrées / sorties (contrats stables, loi n°5 des modules)
    // ------------------------------------------------------------------

    public record CommandeCreation(String code, String nom, TypeStructure type, String region,
                                   String province, String commune, BigDecimal latitude,
                                   BigDecimal longitude) {
    }

    public record MiseAJour(String nom, TypeStructure type, String region, String province,
                            String commune, BigDecimal latitude, BigDecimal longitude) {
    }

    /** Agrégat simple par région : total, actives, répartition par type. */
    public record ArborescenceRegion(String region, long total, long actives,
                                     Map<String, Long> parType) {
    }

    private final StructureRepository structureRepository;
    private final AuditRecorder auditRecorder;

    public StructureService(StructureRepository structureRepository, AuditRecorder auditRecorder) {
        this.structureRepository = structureRepository;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Création — mnémonique unique, géolocalisation optionnelle appariée
    // ------------------------------------------------------------------

    @Transactional
    public StructureEntity creer(CommandeCreation commande, UUID acteur) {
        String code = commande.code() == null ? null : commande.code().trim();
        exigerCoherenceGps(commande.latitude(), commande.longitude());

        // Mnémonique officielle unique : la garde applicative est
        // insensible à la casse (plus stricte que l'index SQL exact).
        structureRepository.findByCodeIgnoreCase(code).ifPresent(existant -> {
            audit(acteur, "STRUCTURE_CREATION_REFUSED", "structure", null, null,
                    "CODE_DEJA_UTILISE", AuditEntryEntity.Result.DENIED,
                    Map.of("code", code));
            throw new CodeStructureDejaUtiliseException(code);
        });

        StructureEntity structure = structureRepository.save(new StructureEntity(
                UuidV7.next(), code, commande.nom().trim(), commande.type(),
                blanchi(commande.region()), blanchi(commande.province()),
                blanchi(commande.commune()), commande.latitude(), commande.longitude()));

        audit(acteur, "STRUCTURE_CREATED", "structure", structure.getId(), structure.getId(),
                null, AuditEntryEntity.Result.SUCCESS,
                Map.of("code", structure.getCode(), "type", structure.getType().getCode(),
                        "region", String.valueOf(structure.getRegion())));

        LOG.info("Structure créée code={} type={} region={}", structure.getCode(),
                structure.getType().getCode(), structure.getRegion());
        return structure;
    }

    // ------------------------------------------------------------------
    // Mise à jour — nom / type / localisation (l'activation est à part)
    // ------------------------------------------------------------------

    @Transactional
    public StructureEntity mettreAJour(UUID id, MiseAJour miseAJour, UUID acteur) {
        exigerCoherenceGps(miseAJour.latitude(), miseAJour.longitude());

        StructureEntity structure = charger(id);
        List<String> champsModifies = new ArrayList<>();
        if (miseAJour.nom() != null && !miseAJour.nom().isBlank()) {
            champsModifies.add("nom");
        }
        if (miseAJour.type() != null) {
            champsModifies.add("type");
        }
        if (miseAJour.region() != null) {
            champsModifies.add("region");
        }
        if (miseAJour.province() != null) {
            champsModifies.add("province");
        }
        if (miseAJour.commune() != null) {
            champsModifies.add("commune");
        }
        if (miseAJour.latitude() != null) {
            champsModifies.add("latitude");
        }
        if (miseAJour.longitude() != null) {
            champsModifies.add("longitude");
        }

        structure.mettreAJour(
                miseAJour.nom() == null || miseAJour.nom().isBlank()
                        ? null : miseAJour.nom().trim(),
                miseAJour.type(),
                blanchi(miseAJour.region()), blanchi(miseAJour.province()),
                blanchi(miseAJour.commune()),
                miseAJour.latitude(), miseAJour.longitude());
        structureRepository.save(structure);

        audit(acteur, "STRUCTURE_UPDATED", "structure", structure.getId(), structure.getId(),
                null, AuditEntryEntity.Result.SUCCESS,
                Map.of("champs", champsModifies.isEmpty() ? List.of() : List.copyOf(champsModifies)));

        return structure;
    }

    // ------------------------------------------------------------------
    // Désactivation / réactivation — SOFT, jamais de DELETE, 409 si déjà
    // ------------------------------------------------------------------

    @Transactional
    public StructureEntity desactiver(UUID id, UUID acteur) {
        StructureEntity structure = charger(id);
        if (!structure.isActive()) {
            audit(acteur, "STRUCTURE_DEACTIVATION_REFUSED", "structure", structure.getId(),
                    structure.getId(), "DEJA_INACTIVE", AuditEntryEntity.Result.DENIED,
                    Map.of("active", false));
            throw new IllegalStructureTransitionException("inactive", "inactive",
                    "La structure " + structure.getCode() + " est déjà inactive");
        }
        structure.changerActivation(false);
        structureRepository.save(structure);

        audit(acteur, "STRUCTURE_DEACTIVATED", "structure", structure.getId(), structure.getId(),
                null, AuditEntryEntity.Result.SUCCESS,
                Map.of("from", "active", "to", "inactive"));
        return structure;
    }

    @Transactional
    public StructureEntity reactiver(UUID id, UUID acteur) {
        StructureEntity structure = charger(id);
        if (structure.isActive()) {
            audit(acteur, "STRUCTURE_ACTIVATION_REFUSED", "structure", structure.getId(),
                    structure.getId(), "DEJA_ACTIVE", AuditEntryEntity.Result.DENIED,
                    Map.of("active", true));
            throw new IllegalStructureTransitionException("active", "active",
                    "La structure " + structure.getCode() + " est déjà active");
        }
        structure.changerActivation(true);
        structureRepository.save(structure);

        audit(acteur, "STRUCTURE_ACTIVATED", "structure", structure.getId(), structure.getId(),
                null, AuditEntryEntity.Result.SUCCESS,
                Map.of("from", "inactive", "to", "active"));
        return structure;
    }

    // ------------------------------------------------------------------
    // Lecture — détail, recherche filtrée, arborescence par région
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StructureEntity trouver(UUID id) {
        return charger(id);
    }

    /**
     * Recherche de l'annuaire : région (exacte), type (exact), texte
     * (contenu, code ou nom), active (exact). Chaque filtre est
     * optionnel — sans filtre, tout l'annuaire (plafonné).
     */
    @Transactional(readOnly = true)
    public List<StructureEntity> rechercher(String region, TypeStructure type, String texte,
                                            Boolean active) {
        Specification<StructureEntity> specification = Specification.where(null);
        if (region != null && !region.isBlank()) {
            String cible = region.trim().toLowerCase();
            specification = specification.and((root, requete, cb) ->
                    cb.equal(cb.lower(root.get("region")), cible));
        }
        if (type != null) {
            specification = specification.and((root, requete, cb) ->
                    cb.equal(root.get("type"), type));
        }
        if (texte != null && !texte.isBlank()) {
            String motif = "%" + texte.trim().toLowerCase() + "%";
            specification = specification.and((root, requete, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), motif),
                    cb.like(cb.lower(root.get("nom")), motif)));
        }
        if (active != null) {
            specification = specification.and((root, requete, cb) ->
                    cb.equal(root.get("active"), active));
        }
        return structureRepository.findAll(specification,
                Sort.by(Sort.Order.asc("nom"), Sort.Order.asc("code")));
    }

    /**
     * Arborescence par région (agrégation simple) : total, actives et
     * répartition par type — la carte nationale de couverture sanitaire.
     * Les structures sans région sont regroupées sous {@code null}.
     */
    @Transactional(readOnly = true)
    public List<ArborescenceRegion> arborescence() {
        Map<String, AgregatRegion> parRegion = new LinkedHashMap<>();
        for (StructureEntity structure : structureRepository.findAll()) {
            String region = structure.getRegion();
            AgregatRegion agregat = parRegion.computeIfAbsent(region,
                    r -> new AgregatRegion(r));
            agregat.total++;
            if (structure.isActive()) {
                agregat.actives++;
            }
            agregat.parType.merge(structure.getType().getCode(), 1L, Long::sum);
        }
        return parRegion.values().stream()
                .map(a -> new ArborescenceRegion(a.region, a.total, a.actives,
                        Map.copyOf(a.parType)))
                .sorted(Comparator.comparing(ArborescenceRegion::region,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Cumulateur interne de l'agrégation par région. */
    private static final class AgregatRegion {
        private final String region;
        private long total;
        private long actives;
        private final Map<String, Long> parType = new LinkedHashMap<>();

        private AgregatRegion(String region) {
            this.region = region;
        }
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    private StructureEntity charger(UUID id) {
        return structureRepository.findById(id)
                .orElseThrow(() -> new StructureIntrouvableException(id));
    }

    /** La géolocalisation va par PAIRE : une coordonnée orpheline est une erreur de saisie. */
    private static void exigerCoherenceGps(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException(
                    "Géolocalisation incomplète : latitude et longitude se renseignent ENSEMBLE");
        }
    }

    /** null / blanc → null (les colonnes de découpage sont optionnelles). */
    private static String blanchi(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }

    private void audit(UUID acteur, String action, String entite, UUID entiteId,
                       UUID facilityId, String raison, AuditEntryEntity.Result resultat,
                       Map<String, Object> details) {
        auditRecorder.record(acteur, action, entite, entiteId, facilityId, raison, resultat,
                details);
    }
}
