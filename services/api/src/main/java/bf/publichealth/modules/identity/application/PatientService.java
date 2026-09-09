package bf.publichealth.modules.identity.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.common.UuidV7;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.identity.adapter.persistence.IdentityMatchEntity;
import bf.publichealth.modules.identity.adapter.persistence.IdentityMatchRepository;
import bf.publichealth.modules.identity.adapter.persistence.MergeLogEntity;
import bf.publichealth.modules.identity.adapter.persistence.MergeLogRepository;
import bf.publichealth.modules.identity.adapter.persistence.PatientEntity;
import bf.publichealth.modules.identity.adapter.persistence.PatientIdentifierEntity;
import bf.publichealth.modules.identity.adapter.persistence.PatientIdentifierRepository;
import bf.publichealth.modules.identity.adapter.persistence.PatientNameEntity;
import bf.publichealth.modules.identity.adapter.persistence.PatientNameRepository;
import bf.publichealth.modules.identity.adapter.persistence.PatientRepository;
import bf.publichealth.modules.identity.adapter.persistence.PatientTelecomEntity;
import bf.publichealth.modules.identity.adapter.persistence.PatientTelecomRepository;
import bf.publichealth.modules.identity.domain.IdentityMatcher;
import bf.publichealth.modules.identity.domain.PatientDuplicateException;
import bf.publichealth.modules.identity.domain.PatientMergedException;

/**
 * Cas d'usage identité & MPI — épique E1.
 *
 * <p>Création : idempotente (clé client, rejeu = même dossier), précédée de
 * la recherche miroir. Un doublon ne crée RIEN : l'agent reçoit les candidats
 * (409 = contrat UX) et tranche — forceCreate pour un vrai distinct, revue
 * de rapprochement pour une fusion. La fusion est irréversible et tracée
 * (merge_log + audit + chaînage).</p>
 */
@Service
public class PatientService {

    // ------------------------------------------------------------------
    // Entrées / sorties (contrats stables, loi n°5 des modules)
    // ------------------------------------------------------------------

    public record NameInput(String use, String family, String given) {
    }

    public record TelecomInput(String system, String value, String use) {
    }

    public record IdentifierInput(String system, String value) {
    }

    public record CreatePatientCommand(UUID clientRequestId, boolean forceCreate,
                                       UUID duplicateOfRejected, String gender,
                                       LocalDate birthDate, boolean birthDateApproximative,
                                       List<NameInput> names, List<TelecomInput> telecoms,
                                       List<IdentifierInput> identifiers,
                                       UUID createdBy) {
    }

    public record PatientCreation(PatientAggregate aggregate, boolean replayed) {
    }

    public record PatientAggregate(PatientEntity patient, List<PatientNameEntity> names,
                                   List<PatientTelecomEntity> telecoms,
                                   List<PatientIdentifierEntity> identifiers) {
    }

    public record MatchReview(UUID matchId, String decision, UUID masterId,
                              String motif, UUID reviewedBy) {
    }

    public record MergeOutcome(MergeLogEntity mergeLog, PatientEntity master,
                               PatientEntity merged) {
    }

    private final PatientRepository patientRepository;
    private final PatientNameRepository nameRepository;
    private final PatientTelecomRepository telecomRepository;
    private final PatientIdentifierRepository identifierRepository;
    private final IdentityMatchRepository matchRepository;
    private final MergeLogRepository mergeLogRepository;
    private final PhReferenceGenerator referenceGenerator;
    private final AuditRecorder auditRecorder;

    public PatientService(PatientRepository patientRepository,
                          PatientNameRepository nameRepository,
                          PatientTelecomRepository telecomRepository,
                          PatientIdentifierRepository identifierRepository,
                          IdentityMatchRepository matchRepository,
                          MergeLogRepository mergeLogRepository,
                          PhReferenceGenerator referenceGenerator,
                          AuditRecorder auditRecorder) {
        this.patientRepository = patientRepository;
        this.nameRepository = nameRepository;
        this.telecomRepository = telecomRepository;
        this.identifierRepository = identifierRepository;
        this.matchRepository = matchRepository;
        this.mergeLogRepository = mergeLogRepository;
        this.referenceGenerator = referenceGenerator;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------
    // Création — idempotente + recherche miroir + 409 = contrat UX
    // ------------------------------------------------------------------

    @Transactional
    public PatientCreation create(CreatePatientCommand cmd) {
        // 1. Idempotence offline-first : une clé client = un dossier, jamais deux.
        if (cmd.clientRequestId() != null) {
            var existant = patientRepository.findByClientRequestId(cmd.clientRequestId());
            if (existant.isPresent()) {
                audit(cmd.createdBy(), "PATIENT_CREATED", "patient", existant.get().getId(),
                        null, "REJOU_CLIENT_REQUEST_ID", AuditEntryEntity.Result.SUCCESS,
                        Map.of("replayed", true, "phReference", existant.get().getPhReference()));
                return new PatientCreation(charger(existant.get()), true);
            }
        }

        // 2. Le créateur a vu le 409 et tranche : « c'est bien un patient distinct ».
        //    La décision est portée par l'audit de création (motif + candidat
        //    rejeté) ; identity_match reste réservé aux rapprochements entre
        //    dossiers EXISTANTS (sa colonne candidate_b est NOT NULL en base).

        // 3. Recherche miroir : rien ne se crée tant qu'un doute existe.
        if (!cmd.forceCreate()) {
            List<PatientDuplicateException.Candidate> candidats = detecterDoublons(cmd);
            if (!candidats.isEmpty()) {
                // L'audit en REQUIRES_NEW survit au rollback : on trace le REFUS,
                // pas seulement les succès (les échecs valent de l'or en MPI).
                audit(cmd.createdBy(), "PATIENT_DUPLICATE_DETECTED", "patient", null,
                        null, null, AuditEntryEntity.Result.DENIED,
                        Map.of("candidates", candidats.size(),
                               "topScore", candidats.get(0).score(),
                               "method", candidats.get(0).method()));
                throw new PatientDuplicateException(candidats);
            }
        }

        // 4. Création effective : référence lisible + uuid v7 + traits.
        PatientEntity patient = new PatientEntity(UuidV7.next(),
                referenceGenerator.nextReference(), cmd.clientRequestId(),
                cmd.gender(), cmd.birthDate(), cmd.birthDateApproximative());
        patientRepository.save(patient);

        for (NameInput name : cmd.names()) {
            nameRepository.save(new PatientNameEntity(UuidV7.next(), patient.getId(),
                    name.use() == null ? "official" : name.use(), name.family(), name.given()));
        }
        for (TelecomInput telecom : cmd.telecoms()) {
            telecomRepository.save(new PatientTelecomEntity(UuidV7.next(), patient.getId(),
                    telecom.system(), telecom.value().strip(), telecom.use()));
        }
        for (IdentifierInput identifier : cmd.identifiers()) {
            identifierRepository.save(new PatientIdentifierEntity(UuidV7.next(), patient.getId(),
                    identifier.system(), identifier.value().strip()));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("phReference", patient.getPhReference());
        details.put("names", cmd.names().size());
        details.put("identifiers", cmd.identifiers().size());
        if (cmd.forceCreate()) {
            details.put("forceCreate", true);
            if (cmd.duplicateOfRejected() != null) {
                details.put("rejectedDuplicate", cmd.duplicateOfRejected());
            }
        }

        audit(cmd.createdBy(), "PATIENT_CREATED", "patient", patient.getId(), null,
                cmd.forceCreate() ? "CREATION_FORCEE_APRES_DOUBLON" : null,
                AuditEntryEntity.Result.SUCCESS, details);

        return new PatientCreation(charger(patient), false);
    }

    /**
     * Recherche miroir locale : candidats par identifiant national, téléphone,
     * puis patronyme (préfixe normalisé). Score déterministe IdentityMatcher.
     */
    private List<PatientDuplicateException.Candidate> detecterDoublons(CreatePatientCommand cmd) {
        NameInput official = cmd.names().isEmpty() ? null : cmd.names().get(0);
        if (official == null) {
            return List.of();
        }

        Set<UUID> idsCandidats = new LinkedHashSet<>();
        for (IdentifierInput identifier : cmd.identifiers()) {
            if (!"LOCAL".equals(identifier.system())) {
                identifierRepository.findByValue(identifier.value().strip())
                        .forEach(i -> idsCandidats.add(i.getPatientId()));
            }
        }
        for (TelecomInput telecom : cmd.telecoms()) {
            telecomRepository.findByValue(telecom.value().strip())
                    .forEach(t -> idsCandidats.add(t.getPatientId()));
        }
        String patronymeNormalise = IdentityMatcher.normalize(official.family());
        if (patronymeNormalise.length() >= 3) {
            String prefix = patronymeNormalise.substring(0, 3);
            nameRepository.findByFamilyPrefix(prefix)
                    .forEach(n -> idsCandidats.add(n.getPatientId()));
        }
        // Les dossiers fusionnés ne sont jamais candidats — ils n'existent plus.
        idsCandidats.removeIf(id -> patientRepository.findById(id)
                .map(p -> Boolean.FALSE.equals(p.getActive()) || p.getMasterId() != null)
                .orElse(true));

        Set<String> telephones = new HashSet<>();
        for (TelecomInput t : cmd.telecoms()) {
            telephones.add(t.value().strip());
        }
        Set<String> nationaux = new HashSet<>();
        for (IdentifierInput i : cmd.identifiers()) {
            if (!"LOCAL".equals(i.system())) {
                nationaux.add(i.system() + ":" + i.value().strip());
            }
        }

        List<PatientDuplicateException.Candidate> candidats = new ArrayList<>();
        for (UUID id : idsCandidats) {
            PatientAggregate existant = charger(patientRepository.findById(id).orElseThrow());
            PatientNameEntity nomExistant = existant.names().isEmpty() ? null : existant.names().get(0);

            Set<String> telsExistant = new HashSet<>();
            existant.telecoms().forEach(t -> telsExistant.add(t.getValue()));
            Set<String> nationauxExistant = new HashSet<>();
            existant.identifiers().forEach(i -> {
                if (!"LOCAL".equals(i.getSystem())) {
                    nationauxExistant.add(i.getSystem() + ":" + i.getValue());
                }
            });

            var traitsExistant = new IdentityMatcher.PatientTraits(
                    nomExistant == null ? "" : nomExistant.getFamily(),
                    nomExistant == null ? "" : nomExistant.getGiven(),
                    existant.patient().getBirthDate(),
                    existant.patient().isBirthDateApproximative(),
                    telsExistant, nationauxExistant);
            var traitsNouveau = new IdentityMatcher.PatientTraits(
                    official.family(), official.given(), cmd.birthDate(),
                    cmd.birthDateApproximative(), telephones, nationaux);

            IdentityMatcher.MatchResult resultat = IdentityMatcher.score(traitsNouveau, traitsExistant);
            if (resultat.blocking() || resultat.review()) {
                candidats.add(new PatientDuplicateException.Candidate(
                        existant.patient().getId(), existant.patient().getPhReference(),
                        nomExistant == null ? "" : nomExistant.getFamily(),
                        nomExistant == null ? "" : nomExistant.getGiven(),
                        existant.patient().getBirthDate() == null ? null
                                : existant.patient().getBirthDate().toString(),
                        existant.patient().getGender(),
                        resultat.score(), resultat.method(), resultat.blocking()));
            }
        }
        candidats.sort((a, b) -> Double.compare(b.score(), a.score()));
        return candidats;
    }

    // ------------------------------------------------------------------
    // Lecture — dossier doré + recherche miroir publique
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PatientAggregate find(UUID id) {
        PatientEntity patient = patientRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Patient introuvable : " + id));
        if (patient.getMasterId() != null) {
            throw new PatientMergedException(patient.getId(), patient.getMasterId());
        }
        return charger(patient);
    }

    @Transactional(readOnly = true)
    public List<PatientAggregate> search(String family, String given, String phone,
                                         LocalDate birthDate, int limite) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (phone != null && !phone.isBlank()) {
            telecomRepository.findByValue(phone.strip())
                    .forEach(t -> ids.add(t.getPatientId()));
        }
        String patronyme = IdentityMatcher.normalize(family == null ? "" : family);
        if (patronyme.length() >= 2) {
            nameRepository.findByFamilyPrefix(patronyme.substring(0, Math.min(3, patronyme.length())))
                    .forEach(n -> ids.add(n.getPatientId()));
        }
        return ids.stream()
                .map(patientRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(p -> Boolean.TRUE.equals(p.getActive()) && p.getMasterId() == null)
                .filter(p -> birthDate == null || birthDate.equals(p.getBirthDate()))
                .filter(p -> given == null || given.isBlank() || nomContient(p.getId(), given))
                .limit(Math.max(1, limite))
                .map(this::charger)
                .toList();
    }

    private boolean nomContient(UUID patientId, String prenom) {
        return nameRepository.findByPatientId(patientId).stream()
                .anyMatch(n -> IdentityMatcher.normalize(n.getGiven())
                        .contains(IdentityMatcher.normalize(prenom)));
    }

    private PatientAggregate charger(PatientEntity patient) {
        return new PatientAggregate(patient,
                nameRepository.findByPatientId(patient.getId()),
                telecomRepository.findByPatientId(patient.getId()),
                identifierRepository.findByPatientId(patient.getId()));
    }

    // ------------------------------------------------------------------
    // File de revue des rapprochements + fusion irréversible tracée
    // ------------------------------------------------------------------

    @Transactional
    public IdentityMatchEntity createMatch(UUID candidateA, UUID candidateB,
                                            String method, UUID createdBy) {
        if (candidateA.equals(candidateB)) {
            throw new IllegalArgumentException("Rapprochement impossible : un dossier avec lui-meme");
        }
        for (UUID c : List.of(candidateA, candidateB)) {
            PatientEntity p = patientRepository.findById(c)
                    .orElseThrow(() -> new IllegalArgumentException("Patient introuvable : " + c));
            if (p.getMasterId() != null || Boolean.FALSE.equals(p.getActive())) {
                throw new IllegalArgumentException(
                        "Patient " + c + " fusionne ou inactif : rapprochement refuse");
            }
        }
        IdentityMatchEntity match = matchRepository.save(new IdentityMatchEntity(
                UuidV7.next(), candidateA, candidateB, 0.0,
                method == null ? "PROBABILISTIC" : method, "PENDING",
                null, null, null));
        audit(createdBy, "IDENTITY_MATCH_CREATED", "identity_match", match.getId(), null, null,
                AuditEntryEntity.Result.SUCCESS,
                Map.of("candidateA", candidateA, "candidateB", candidateB));
        return match;
    }

    @Transactional(readOnly = true)
    public List<IdentityMatchEntity> matchesEnAttente() {
        return matchRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    /**
     * Revue humaine. ACCEPTED = fusion irréversible : le dossier fusionné
     * est désactivé, pointé vers le maître, et le journal merge_log reçoit
     * l'inventaire exact de ce qui a déménagé. REJECTED = simple trace.
     */
    @Transactional
    public MergeOutcome reviewMatch(MatchReview revue) {
        IdentityMatchEntity match = matchRepository.findById(revue.matchId())
                .orElseThrow(() -> new IllegalArgumentException("Rapprochement introuvable : "
                        + revue.matchId()));
        if (!"PENDING".equals(match.getStatus())) {
            throw new IllegalStateException("Rapprochement deja statue : " + match.getStatus());
        }
        if (revue.motif() == null || revue.motif().isBlank()) {
            throw new IllegalArgumentException("Motif OBLIGATOIRE pour statuer un rapprochement");
        }

        if ("REJECTED".equals(revue.decision())) {
            match.statuer("REJECTED", revue.reviewedBy(), Instant.now(), revue.motif());
            matchRepository.save(match);
            audit(revue.reviewedBy(), "IDENTITY_MATCH_REJECTED", "identity_match", match.getId(),
                    null, revue.motif(), AuditEntryEntity.Result.SUCCESS,
                    Map.of("candidateA", match.getCandidateA(), "candidateB", match.getCandidateB()));
            return null;
        }
        if (!"ACCEPTED".equals(revue.decision())) {
            throw new IllegalArgumentException("Decision inconnue : " + revue.decision());
        }

        // Fusion : maître = celui désigné par le réviseur, sinon candidate_a.
        UUID masterId = revue.masterId() != null ? revue.masterId() : match.getCandidateA();
        UUID mergedId = masterId.equals(match.getCandidateA())
                ? match.getCandidateB() : match.getCandidateA();
        PatientEntity master = patientRepository.findById(masterId).orElseThrow();
        PatientEntity merged = patientRepository.findById(mergedId).orElseThrow();
        if (master.getMasterId() != null || merged.getMasterId() != null) {
            throw new IllegalStateException("Un des dossiers est deja fusionne — fusion refusee");
        }

        // Inventaire avant déménagement : tout ce qui suit doit être retrouvable.
        List<PatientIdentifierEntity> identifiants = identifierRepository
                .findByPatientId(mergedId);
        Set<String> nationauxMaster = new HashSet<>();
        identifierRepository.findByPatientId(masterId).forEach(i -> {
            if (!"LOCAL".equals(i.getSystem())) {
                nationauxMaster.add(i.getSystem() + ":" + i.getValue());
            }
        });
        Set<String> telsMaster = new HashSet<>();
        telecomRepository.findByPatientId(masterId).forEach(t -> telsMaster.add(t.getValue()));

        List<Map<String, String>> deplaces = new ArrayList<>();
        for (PatientIdentifierEntity i : identifiants) {
            boolean national = !"LOCAL".equals(i.getSystem());
            boolean absentChezMaster = !nationauxMaster.contains(i.getSystem() + ":" + i.getValue());
            if (national && absentChezMaster) {
                i.affecter(masterId);
                identifierRepository.save(i);
                deplaces.add(Map.of("type", "identifier",
                        "system", i.getSystem(), "value", i.getValue()));
            }
        }
        for (PatientTelecomEntity t : telecomRepository.findByPatientId(mergedId)) {
            if (!telsMaster.contains(t.getValue())) {
                t.affecter(masterId);
                telecomRepository.save(t);
                deplaces.add(Map.of("type", "telecom", "value", t.getValue()));
            }
        }

        // Irréversible : master_id posé, dossier désactivé, version incrémentée.
        merged.fusionnerDans(masterId);
        patientRepository.save(merged);
        master.incrementerVersion();
        patientRepository.save(master);

        match.statuer("ACCEPTED", revue.reviewedBy(), Instant.now(), revue.motif());
        matchRepository.save(match);

        Map<String, Object> inventaire = new LinkedHashMap<>();
        inventaire.put("master", Map.of("id", master.getId().toString(),
                "phReference", master.getPhReference()));
        inventaire.put("merged", Map.of("id", merged.getId().toString(),
                "phReference", merged.getPhReference()));
        inventaire.put("deplaces", deplaces);
        inventaire.put("matchId", match.getId().toString());
        MergeLogEntity journal = mergeLogRepository.save(new MergeLogEntity(
                UuidV7.next(), masterId, mergedId, inventaire,
                revue.reviewedBy(), revue.motif()));

        audit(revue.reviewedBy(), "PATIENT_MERGED", "patient", mergedId, null, revue.motif(),
                AuditEntryEntity.Result.SUCCESS,
                Map.of("masterId", masterId, "matchId", match.getId(),
                       "deplaces", deplaces.size()));

        return new MergeOutcome(journal, master, merged);
    }

    @Transactional(readOnly = true)
    public List<MergeLogEntity> journalDesFusions() {
        return mergeLogRepository.findAllByOrderByPerformedAtDesc();
    }

    private void audit(UUID actor, String action, String entity, UUID entityId,
                       UUID facilityId, String reason, AuditEntryEntity.Result result,
                       Map<String, Object> details) {
        auditRecorder.record(actor, action, entity, entityId, facilityId, reason, result, details);
    }
}
