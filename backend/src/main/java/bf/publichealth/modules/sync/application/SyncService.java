package bf.publichealth.modules.sync.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.identity.application.PatientService;
import bf.publichealth.modules.identity.domain.PatientDuplicateException;
import bf.publichealth.modules.sync.adapter.persistence.SyncDeviceEntity;
import bf.publichealth.modules.sync.adapter.persistence.SyncDeviceRepository;
import bf.publichealth.modules.sync.adapter.persistence.SyncOpEntity;
import bf.publichealth.modules.sync.adapter.persistence.SyncOpRepository;
import bf.publichealth.modules.sync.domain.AppareilInconnuException;
import bf.publichealth.modules.sync.domain.CurseurDelta;
import bf.publichealth.modules.sync.domain.CurseurInvalideException;
import bf.publichealth.modules.sync.domain.EntiteDeltaInconnueException;

/**
 * Cas d'usage du protocole de synchronisation offline (épique E2).
 *
 * <p>Loi de fer du lot : UN LOT NE TOMBE JAMAIS EN BLOC. Chaque op est
 * traitée dans sa propre transaction et son verdict vit DANS les
 * résultats ({@code APPLIED}, {@code REJECTED}, {@code CONFLICT}) ;
 * seule une charge globalement invalide répond 400.</p>
 *
 * <p>Idempotence stricte : un opId déjà journalisé (sync.op, V4) rend
 * SON résultat stocké — rejouer un lot entier redonne exactement les
 * mêmes réponses, aucune donnée n'est dupliquée.</p>
 */
@Service
public class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    /** Entités reconnues par l'uplink (miroir ascendant). */
    public static final Set<String> ENTITES_RECONNUES =
            Set.of("patient", "encounter", "observation", "condition");

    private static final int LIMITE_DELTA_DEFAUT = 200;
    private static final int LIMITE_DELTA_MAX = 200;

    private static final Set<String> GENRES = Set.of("male", "female", "other", "unknown");
    private static final Set<String> SYSTEMES_TELECOM = Set.of("phone", "email");
    private static final Set<String> SYSTEMES_IDENTIFIANT =
            Set.of("NUNP", "CNIB", "ANCIEN_REGISTRE", "LOCAL");
    private static final Set<String> USAGES_NOM = Set.of("official", "usual");
    private static final Set<String> STATUTS_OBSERVATION = Set.of("final", "entered-in-error");

    private final SyncOpRepository opRepository;
    private final SyncDeviceRepository deviceRepository;
    private final SyncOpApplier applier;
    private final MiroirPatients miroirPatients;
    private final EcrivainClinique ecrivainClinique;
    private final ObjectMapper objectMapper;

    public SyncService(SyncOpRepository opRepository,
                       SyncDeviceRepository deviceRepository,
                       SyncOpApplier applier,
                       MiroirPatients miroirPatients,
                       EcrivainClinique ecrivainClinique,
                       ObjectMapper objectMapper) {
        this.opRepository = opRepository;
        this.deviceRepository = deviceRepository;
        this.applier = applier;
        this.miroirPatients = miroirPatients;
        this.ecrivainClinique = ecrivainClinique;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Contrats stables (loi n°5 des modules)
    // ------------------------------------------------------------------

    /** Déclaration d'appareil brute, venue du web. */
    public record DeclarationAppareil(UUID userId, String deviceName) {
    }

    /** Appareil déclaré : même deviceId au rejeu, curseur mémorisé. */
    public record Appareil(UUID deviceId, String curseur, boolean dejaConnu) {
    }

    /** Op offline brute telle que le PWA l'envoie. */
    public record OpBrute(UUID opId, String entity, UUID entityId,
                          UUID clientRequestId, UUID userId, JsonNode payload) {
    }

    public record LotBrut(UUID deviceId, UUID userId, List<OpBrute> ops) {
    }

    public record ReponseLot(List<ResultatOp> results) {
    }

    /** Verdict d'une op — LE contrat du protocole uplink. */
    public record ResultatOp(UUID opId, String resultat, JsonNode detail) {

        public static ResultatOp depuis(SyncOpEntity entite, ObjectMapper objectMapper) {
            JsonNode detail = null;
            if (entite.getResultDetail() != null) {
                try {
                    detail = objectMapper.readTree(entite.getResultDetail());
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(
                            "result_detail illisible pour opId " + entite.getOpId(), e);
                }
            }
            return new ResultatOp(entite.getOpId(), entite.getResult(), detail);
        }
    }

    public record Delta(String curseur, boolean hasMore, List<String> entities,
                        List<MiroirPatients.PatientMiroir> patients) {
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync/device — déclaration idempotente
    // ------------------------------------------------------------------

    public Appareil declarerAppareil(DeclarationAppareil declaration) {
        var existant = declaration.userId() != null
                ? deviceRepository.findByUserIdAndDeviceName(declaration.userId(), declaration.deviceName())
                : deviceRepository.findByUserIdIsNullAndDeviceName(declaration.deviceName());
        if (existant.isPresent()) {
            SyncDeviceEntity appareil = existant.get();
            appareil.marquerVu(appareil.getLastSyncCursor());
            deviceRepository.save(appareil);
            LOG.info("Déclaration d'appareil rejouée deviceId={}", appareil.getId());
            return new Appareil(appareil.getId(), appareil.getLastSyncCursor(), true);
        }

        SyncDeviceEntity appareil = new SyncDeviceEntity(UuidV7.next(),
                declaration.userId(), declaration.deviceName());
        try {
            appareil = deviceRepository.save(appareil);
        } catch (DataIntegrityViolationException e) {
            // Course concurrente sur l'index unique COALESCE (V7) :
            // une déclaration parallèle vient d'enregistrer ce couple.
            var rattrape = declaration.userId() != null
                    ? deviceRepository.findByUserIdAndDeviceName(declaration.userId(), declaration.deviceName())
                    : deviceRepository.findByUserIdIsNullAndDeviceName(declaration.deviceName());
            if (rattrape.isPresent()) {
                LOG.info("Déclaration d'appareil en course concurrente, deviceId={}", rattrape.get().getId());
                return new Appareil(rattrape.get().getId(), rattrape.get().getLastSyncCursor(), true);
            }
            throw e;
        }
        LOG.info("Déclaration d'appareil deviceId={}", appareil.getId());
        return new Appareil(appareil.getId(), null, false);
    }

    // ------------------------------------------------------------------
    // POST /api/v1/sync — uplink par lot, idempotent par opId
    // ------------------------------------------------------------------

    public ReponseLot uplink(LotBrut lot) {
        SyncDeviceEntity appareil = null;
        if (lot.deviceId() != null) {
            appareil = deviceRepository.findById(lot.deviceId())
                    .orElseThrow(() -> new AppareilInconnuException(lot.deviceId()));
        }
        UUID userIdLot = lot.userId() != null ? lot.userId()
                : (appareil != null ? appareil.getUserId() : null);

        List<ResultatOp> resultats = new ArrayList<>(lot.ops().size());
        for (OpBrute op : lot.ops()) {
            try {
                resultats.add(traiter(op, userIdLot, appareil));
            } catch (Exception e) {
                // Dernier filet : même un échec de journalisation ne doit
                // jamais faire tomber le lot.
                LOG.warn("Uplink : échec non prévu opId={} entité={} : {}",
                        op.opId(), op.entity(), e.getMessage());
                resultats.add(rejeter(op, userIdLot,
                        "Échec inattendu — voir les logs serveur (opId " + op.opId() + ")"));
            }
        }

        if (appareil != null) {
            appareil.marquerVu(appareil.getLastSyncCursor());
            deviceRepository.save(appareil);
        }

        int appliquees = (int) resultats.stream().filter(r -> SyncOpEntity.APPLIED.equals(r.resultat())).count();
        int rejetees = (int) resultats.stream().filter(r -> SyncOpEntity.REJECTED.equals(r.resultat())).count();
        int conflits = (int) resultats.stream().filter(r -> SyncOpEntity.CONFLICT.equals(r.resultat())).count();
        // Aucune donnée sensible ici : identifiants et compteurs suffisent.
        LOG.info("Uplink deviceId={} ops={} → appliquées={} rejetées={} conflits={}",
                lot.deviceId(), lot.ops().size(), appliquees, rejetees, conflits);
        return new ReponseLot(resultats);
    }

    private ResultatOp traiter(OpBrute op, UUID userIdLot, SyncDeviceEntity appareil) {
        // 1. Idempotence stricte : l'opId existe → SON résultat stocké.
        var stockee = opRepository.findById(op.opId());
        if (stockee.isPresent()) {
            return ResultatOp.depuis(stockee.get(), objectMapper);
        }

        UUID userId = op.userId() != null ? op.userId() : userIdLot;
        UUID deviceId = appareil != null ? appareil.getId() : null;

        // 2. Contrat de protocole : opId = UUID v7 généré côté client.
        if (op.opId().version() != 7) {
            return rejeter(op, userId, "opId doit être un UUID v7 généré côté client (version reçue : "
                    + op.opId().version() + ")");
        }
        // 3. Entité reconnue ?
        if (!ENTITES_RECONNUES.contains(op.entity())) {
            return rejeter(op, userId, "Entité inconnue : « " + op.entity()
                    + " » (reconnues : patient, encounter, observation, condition)");
        }
        // 4. Charge présente ?
        if (op.payload() == null || !op.payload().isObject()) {
            return rejeter(op, userId, "payload requis (objet JSON) pour l'entité " + op.entity());
        }

        try {
            return switch (op.entity()) {
                case "patient" -> traiterPatient(op, userId, deviceId);
                default -> traiterClinique(op, userId, deviceId);
            };
        } catch (PatientDuplicateException e) {
            // 409/doublons = résultat CONFLICT avec candidats — le lot continue.
            return conflit(op, userId, e.getCandidates());
        } catch (DataIntegrityViolationException e) {
            return rejeuConcurrent(op, userId, e);
        }
    }

    // ------------------------------------------------------------------
    // Routage patient — réutilisation intégrale de PatientService
    // ------------------------------------------------------------------

    private ResultatOp traiterPatient(OpBrute op, UUID userId, UUID deviceId) {
        PatientService.CreatePatientCommand commande;
        try {
            commande = objectMapper.convertValue(op.payload(),
                    PatientService.CreatePatientCommand.class);
        } catch (IllegalArgumentException e) {
            return rejeter(op, userId, "Charge patient illisible : " + e.getMessage());
        }

        List<String> violations = validerCommandePatient(commande);
        // Clé d'idempotence : la charge d'abord, l'op en repli.
        UUID clientRequestId = commande.clientRequestId() != null
                ? commande.clientRequestId() : op.clientRequestId();
        if (clientRequestId == null) {
            violations.add("clientRequestId requis pour une création patient offline "
                    + "(dans la charge ou au niveau de l'op)");
        }
        if (!violations.isEmpty()) {
            return rejeter(op, userId, "Charge patient invalide", violations);
        }

        UUID createdBy = commande.createdBy() != null ? commande.createdBy() : userId;
        PatientService.CreatePatientCommand finale = new PatientService.CreatePatientCommand(
                clientRequestId, commande.forceCreate(), commande.duplicateOfRejected(),
                commande.gender(), commande.birthDate(), commande.birthDateApproximative(),
                commande.names(),
                commande.telecoms() == null ? List.of() : commande.telecoms(),
                commande.identifiers() == null ? List.of() : commande.identifiers(),
                createdBy);
        return applier.appliquerPatient(op.opId(), userId, deviceId, finale);
    }

    /**
     * Validation minimale de la charge patient — miroir des contraintes du
     * DTO identity (frontière de module respectée : on valide NOTRE charge
     * entrante, PatientService reste le seul décideur métier).
     */
    private List<String> validerCommandePatient(PatientService.CreatePatientCommand c) {
        List<String> violations = new ArrayList<>();
        if (c.names() == null || c.names().isEmpty()) {
            violations.add("names : au moins un nom est requis");
        } else {
            for (int i = 0; i < c.names().size(); i++) {
                var n = c.names().get(i);
                if (n.family() == null || n.family().isBlank()) {
                    violations.add("names[%d].family : requis".formatted(i));
                }
                if (n.given() == null || n.given().isBlank()) {
                    violations.add("names[%d].given : requis".formatted(i));
                }
                if (n.use() != null && !USAGES_NOM.contains(n.use())) {
                    violations.add("names[%d].use : official ou usual".formatted(i));
                }
            }
        }
        if (c.gender() != null && !GENRES.contains(c.gender())) {
            violations.add("gender : male, female, other ou unknown");
        }
        if (c.birthDate() != null && !c.birthDate().isBefore(LocalDate.now())) {
            violations.add("birthDate : doit être dans le passé");
        }
        if (c.telecoms() != null) {
            for (int i = 0; i < c.telecoms().size(); i++) {
                var t = c.telecoms().get(i);
                if (t.system() != null && !SYSTEMES_TELECOM.contains(t.system())) {
                    violations.add("telecoms[%d].system : phone ou email".formatted(i));
                }
                if (t.value() == null || t.value().isBlank()) {
                    violations.add("telecoms[%d].value : requis".formatted(i));
                }
            }
        }
        if (c.identifiers() != null) {
            for (int i = 0; i < c.identifiers().size(); i++) {
                var id = c.identifiers().get(i);
                if (id.system() == null || !SYSTEMES_IDENTIFIANT.contains(id.system())) {
                    violations.add("identifiers[%d].system : NUNP, CNIB, ANCIEN_REGISTRE ou LOCAL".formatted(i));
                }
                if (id.value() == null || id.value().isBlank()) {
                    violations.add("identifiers[%d].value : requis".formatted(i));
                }
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------
    // Routage clinical — append-only, UUID côté client
    // ------------------------------------------------------------------

    private ResultatOp traiterClinique(OpBrute op, UUID userId, UUID deviceId) {
        // Identifiant : l'op d'abord, la charge ("id") en repli toléré.
        UUID entityId = op.entityId();
        if (entityId == null) {
            JsonNode idCharge = op.payload().path("id");
            if (idCharge.isTextual()) {
                try {
                    entityId = UUID.fromString(idCharge.asText());
                } catch (IllegalArgumentException e) {
                    return rejeter(op, userId, "id illisible dans la charge : " + idCharge.asText());
                }
            }
        }
        if (entityId == null) {
            return rejeter(op, userId,
                    "entityId requis (UUID v7 généré côté client) pour l'entité " + op.entity());
        }
        if (entityId.version() != 7) {
            return rejeter(op, userId, "entityId doit être un UUID v7 généré côté client (version reçue : "
                    + entityId.version() + ")");
        }
        // Append-only : un UUID ne vit qu'une fois — l'idempotence passe par opId.
        if (ecrivainClinique.existe(op.entity(), entityId)) {
            return rejeter(op, userId, "Identifiant déjà utilisé : " + entityId
                    + " — clinical est append-only, l'idempotence se fait par opId");
        }

        try {
            return switch (op.entity()) {
                case "encounter" -> {
                    var charge = objectMapper.convertValue(op.payload(), EcrivainClinique.Encounter.class);
                    List<String> violations = validerEncounter(charge);
                    if (!violations.isEmpty()) {
                        yield rejeter(op, userId, "Charge encounter invalide", violations);
                    }
                    yield applier.appliquerEncounter(op.opId(), userId, deviceId, entityId, charge);
                }
                case "observation" -> {
                    var charge = objectMapper.convertValue(op.payload(), EcrivainClinique.Observation.class);
                    List<String> violations = validerObservation(charge);
                    if (!violations.isEmpty()) {
                        yield rejeter(op, userId, "Charge observation invalide", violations);
                    }
                    // FK encounter : rejet PROPRE (sans casser le lot) si le parent n'existe pas.
                    if (!ecrivainClinique.existe("encounter", charge.encounterId())) {
                        yield rejeter(op, userId, "Encounter référencé inexistant : " + charge.encounterId());
                    }
                    yield applier.appliquerObservation(op.opId(), userId, deviceId, entityId, charge);
                }
                case "condition" -> {
                    var charge = objectMapper.convertValue(op.payload(), EcrivainClinique.Condition.class);
                    List<String> violations = validerCondition(charge);
                    if (!violations.isEmpty()) {
                        yield rejeter(op, userId, "Charge condition invalide", violations);
                    }
                    if (!ecrivainClinique.existe("encounter", charge.encounterId())) {
                        yield rejeter(op, userId, "Encounter référencé inexistant : " + charge.encounterId());
                    }
                    yield applier.appliquerCondition(op.opId(), userId, deviceId, entityId, charge);
                }
                default -> rejeter(op, userId, "Entité inconnue : " + op.entity());
            };
        } catch (IllegalArgumentException e) {
            return rejeter(op, userId, "Charge " + op.entity() + " illisible : " + e.getMessage());
        }
    }

    private List<String> validerEncounter(EcrivainClinique.Encounter c) {
        List<String> violations = new ArrayList<>();
        if (c.patientId() == null) {
            violations.add("patientId : requis");
        }
        if (c.facilityId() == null) {
            violations.add("facilityId : requis");
        }
        if (c.encounterClass() == null || c.encounterClass().isBlank()) {
            violations.add("encounterClass : requis");
        } else if (c.encounterClass().length() > 24) {
            violations.add("encounterClass : 24 caractères maximum");
        }
        if (c.startedAt() == null) {
            violations.add("startedAt : requis");
        }
        if (c.startedAt() != null && c.endedAt() != null && c.endedAt().isBefore(c.startedAt())) {
            violations.add("endedAt : ne peut pas précéder startedAt");
        }
        return violations;
    }

    private List<String> validerObservation(EcrivainClinique.Observation c) {
        List<String> violations = new ArrayList<>();
        if (c.encounterId() == null) {
            violations.add("encounterId : requis");
        }
        if (c.patientId() == null) {
            violations.add("patientId : requis");
        }
        if (c.code() == null || c.code().isBlank()) {
            violations.add("code : requis");
        }
        if (c.status() != null && !STATUTS_OBSERVATION.contains(c.status())) {
            violations.add("status : final ou entered-in-error");
        }
        if (c.effectiveAt() == null) {
            violations.add("effectiveAt : requis");
        }
        return violations;
    }

    private List<String> validerCondition(EcrivainClinique.Condition c) {
        List<String> violations = new ArrayList<>();
        if (c.encounterId() == null) {
            violations.add("encounterId : requis");
        }
        if (c.patientId() == null) {
            violations.add("patientId : requis");
        }
        if (c.code() == null || c.code().isBlank()) {
            violations.add("code : requis");
        }
        if (c.clinicalStatus() != null && c.clinicalStatus().length() > 24) {
            violations.add("clinicalStatus : 24 caractères maximum");
        }
        return violations;
    }

    // ------------------------------------------------------------------
    // Journalisation des verdicts (hors transaction applicative)
    // ------------------------------------------------------------------

    private ResultatOp conflit(OpBrute op, UUID userId,
                               List<PatientDuplicateException.Candidate> candidats) {
        Map<String, Object> detail = new LinkedHashMap<>();
        // Suggestion 4 / Q42 — même aveuglement que le 409 REST : les
        // candidats ne reviennent vers l'appareil qui rejoue l'op que
        // s'il porte patient:lire (en pratique : jeton de service staff).
        // Posture ouverte / jeton sans permission → COMPTEUR seul.
        if (bf.publichealth.common.ContexteAppelant.permission(
                bf.publichealth.modules.administration.domain.RolesPermissions.PATIENT_LIRE)) {
            detail.put("reason", "Patient probablement déjà enregistré — voir candidates");
            detail.put("candidates", candidats);
        } else {
            detail.put("reason", "Patient probablement déjà enregistré — candidats masqués "
                    + "(permission patient:lire requise pour les consulter)");
            detail.put("candidatesRedacted", true);
            detail.put("candidatesCount", candidats.size());
        }
        return persister(new SyncOpEntity(op.opId(), userId, op.entity(), null,
                SyncOpEntity.CONFLICT, json(detail)));
    }

    private ResultatOp rejeter(OpBrute op, UUID userId, String raison) {
        return rejeter(op, userId, raison, null);
    }

    private ResultatOp rejeter(OpBrute op, UUID userId, String raison, List<String> violations) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", raison);
        if (violations != null && !violations.isEmpty()) {
            detail.put("violations", violations);
        }
        return persister(new SyncOpEntity(op.opId(), userId, op.entity(), op.entityId(),
                SyncOpEntity.REJECTED, json(detail)));
    }

    /**
     * Course concurrente : la même op arrive en parallèle. L'opId vient
     * probablement d'être journalisé par la jumelle — on rend son
     * résultat ; sinon c'est l'identifiant métier qui a perdu la course.
     */
    private ResultatOp rejeuConcurrent(OpBrute op, UUID userId, DataIntegrityViolationException cause) {
        var stockee = opRepository.findById(op.opId());
        if (stockee.isPresent()) {
            return ResultatOp.depuis(stockee.get(), objectMapper);
        }
        if (op.entityId() != null && ecrivainClinique.existe(op.entity(), op.entityId())) {
            return rejeter(op, userId, "Identifiant déjà utilisé : " + op.entityId()
                    + " — l'idempotence se fait par opId");
        }
        LOG.warn("Uplink : contrainte d'intégrité en échec opId={} entité={} : {}",
                op.opId(), op.entity(), cause.getMostSpecificCause().getMessage());
        return rejeter(op, userId, "Contrainte d'intégrité en échec — cette op seule est rejetée");
    }

    /** Persiste le verdict ; si l'opId a été pris entre-temps, on le relit. */
    private ResultatOp persister(SyncOpEntity entite) {
        try {
            return ResultatOp.depuis(opRepository.save(entite), objectMapper);
        } catch (DataIntegrityViolationException e) {
            return opRepository.findById(entite.getOpId())
                    .map(stockee -> ResultatOp.depuis(stockee, objectMapper))
                    .orElseThrow(() -> e);
        }
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Détail de résultat illisible", e);
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v1/sync/delta — miroir descendant patient
    // ------------------------------------------------------------------

    public Delta delta(String curseurBrut, String entitesBrut, Integer limite, UUID deviceId) {
        // Entités demandées : patient seul aujourd'hui (miroir minimal E2).
        LinkedHashSet<String> entites = new LinkedHashSet<>();
        if (entitesBrut != null && !entitesBrut.isBlank()) {
            for (String brute : entitesBrut.split(",")) {
                String entite = brute.strip().toLowerCase();
                if (entite.isEmpty()) {
                    continue;
                }
                if (!"patient".equals(entite)) {
                    throw new EntiteDeltaInconnueException(entite);
                }
                entites.add(entite);
            }
        }
        if (entites.isEmpty()) {
            entites.add("patient");
        }

        SyncDeviceEntity appareil = null;
        if (deviceId != null) {
            appareil = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new AppareilInconnuException(deviceId));
        }

        // Curseur effectif : paramètre explicite > curseur mémorisé de
        // l'appareil > origine (tout l'historique).
        CurseurDelta curseur;
        if (curseurBrut != null && !curseurBrut.isBlank()) {
            curseur = CurseurDelta.parse(curseurBrut);
        } else if (appareil != null && appareil.getLastSyncCursor() != null) {
            curseur = CurseurDelta.parse(appareil.getLastSyncCursor());
        } else {
            curseur = CurseurDelta.ORIGINE;
        }

        int limiteEffective = Math.max(1, Math.min(
                limite == null ? LIMITE_DELTA_DEFAUT : limite, LIMITE_DELTA_MAX));

        MiroirPatients.PageDelta page = miroirPatients.patientsApres(curseur, limiteEffective);

        if (appareil != null) {
            appareil.marquerVu(page.curseur().encoder());
            deviceRepository.save(appareil);
        }

        LOG.info("Delta deviceId={} limite={} → {} patients, hasMore={}, curseur={}",
                deviceId, limiteEffective, page.patients().size(), page.hasMore(),
                page.curseur().encoder());
        return new Delta(page.curseur().encoder(), page.hasMore(), List.copyOf(entites),
                page.patients());
    }
}
