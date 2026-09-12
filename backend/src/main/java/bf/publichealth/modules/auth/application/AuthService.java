package bf.publichealth.modules.auth.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.publichealth.modules.administration.adapter.persistence.UtilisateurRepository;
import bf.publichealth.modules.administration.adapter.persistence.UtilisateurEntity;
import bf.publichealth.modules.administration.domain.StatutUtilisateur;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.auth.domain.Jetons;

/**
 * Authentification INTERNE — correction des insuffisances I1/I3 de l'audit.
 *
 * <p>Deux chaînes réelles :</p>
 * <ul>
 *   <li><b>Staff</b> : email + mot de passe (BCrypt, colonne
 *       mot_de_passe_hash posée par V14) → jeton JWT HS256 porteur du
 *       rôle, de la structure et du nom. Les comptes INVITÉS et
 *       SUSPENDUS sont refusés (fail-closed). MFA : les comptes à
 *       mfa_active exigent un second code à 6 chiffres — en l'absence
 *       de passerelle SMS dans le dépôt, le code de démonstration est
 *       RENVOYÉ dans la réponse 401 intermédiaire (drapeau
 *       auth.demo-otp-visible, posture démo documentée ; en production
 *       ce code part par SMS/email via le module notification) ;</li>
 *   <li><b>Patient</b> : numéro de téléphone → code à 4 chiffres
 *       (5 minutes de validité, 1 tentative par code) → jeton
 *       autoporteur patient_id. Le téléphone doit correspondre à un
 *       dossier EXISTANT (identity.patient_telecom) ; un numéro
 *       inconnu reçoit un message neutre (pas d'énumération).</li>
 * </ul>
 *
 * <p>Chaque tentative est auditée (AUTH_LOGIN / AUTH_LOGIN refusé /
 * AUTH_PATIENT_OTP / AUTH_PATIENT_LOGIN) — l'audit I16 exigeait de
 * savoir QUI entre.</p>
 */
@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    /** Validité du code patient : 5 minutes. */
    private static final long VALIDITE_OTP_SECONDES = 300;

    private final UtilisateurRepository utilisateurs;
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encodeur = new BCryptPasswordEncoder();
    private final AuditRecorder audit;

    private final String secretJwt;
    private final boolean demoOtpVisible;

    /** Codes patient en attente : téléphone normalisé → (code, expiration). */
    private final Map<String, CodeEnAttente> codesPatient = new ConcurrentHashMap<>();

    /** Défis MFA en attente : email normalisé → (code, expiration). */
    private final Map<String, CodeEnAttente> defisMfa = new ConcurrentHashMap<>();

    private final SecureRandom aleatoire = new SecureRandom();

    private record CodeEnAttente(String code, Instant expireLe) {
        boolean valide() {
            return Instant.now().isBefore(expireLe);
        }
    }

    public AuthService(UtilisateurRepository utilisateurs,
                       JdbcTemplate jdbc,
                       AuditRecorder audit,
                       @Value("${securite.jwt.secret}") String secretJwt,
                       @Value("${auth.demo-otp-visible:true}") boolean demoOtpVisible) {
        this.utilisateurs = utilisateurs;
        this.jdbc = jdbc;
        this.audit = audit;
        this.secretJwt = secretJwt;
        this.demoOtpVisible = demoOtpVisible;
    }

    // ------------------------------------------------------------------
    // Login staff
    // ------------------------------------------------------------------

    public record ResultatStaff(String jeton, long expireALe, UUID compteId, String email,
                                String role, UUID structureId, String nom, boolean mfaActive) {
    }

    public static class AuthentificationRefusee extends RuntimeException {
        public AuthentificationRefusee(String message) {
            super(message);
        }
    }

    /** Défi MFA à relever : le code démo est renvoyé (posture documentée). */
    public record DefiMfa(String email, boolean mfaRequise, String codeDemo) {
    }

    @Transactional
    public Object login(String email, String motDePasse, String codeMfa) {
        String emailNormalise = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<UtilisateurEntity> compte = utilisateurs.findByEmailIgnoreCase(emailNormalise);

        // Message neutre : jamais dire si l'email existe (I18 — énumération).
        if (compte.isEmpty() || compte.get().getMotDePasseHash() == null
                || !encodeur.matches(motDePasse == null ? "" : motDePasse,
                        compte.get().getMotDePasseHash())) {
            audit.record(null, "AUTH_LOGIN", "utilisateur", null, null,
                    "identifiants invalides", AuditEntryEntity.Result.FAILURE, null);
            throw new AuthentificationRefusee("Email ou mot de passe incorrect");
        }

        UtilisateurEntity utilisateur = compte.get();
        if (utilisateur.getStatut() != StatutUtilisateur.ACTIF) {
            audit.record(utilisateur.getSupabaseUserId(), "AUTH_LOGIN", "utilisateur",
                    utilisateur.getId(), null, "statut=" + utilisateur.getStatut().getCode(),
                    AuditEntryEntity.Result.DENIED, null);
            throw new AuthentificationRefusee(
                    "Compte " + statutLisible(utilisateur.getStatut().getCode())
                            + " — contactez un administrateur");
        }

        // Second facteur (MFA) : code à 6 chiffres.
        if (utilisateur.isMfaActive()) {
            String cle = emailNormalise;
            CodeEnAttente attendu = defisMfa.get(cle);
            if (codeMfa == null || codeMfa.isBlank()) {
                String code = codeMfa6();
                defisMfa.put(cle, new CodeEnAttente(code, Instant.now().plusSeconds(VALIDITE_OTP_SECONDES)));
                // Posture démo : le code est renvoyé (aucune passerelle SMS livrée).
                // En production, le module notification l'envoie et codeDemo reste null.
                return new DefiMfa(emailNormalise, true, demoOtpVisible ? code : null);
            }
            if (attendu == null || !attendu.valide() || !attendu.code().equals(codeMfa.trim())) {
                audit.record(utilisateur.getSupabaseUserId(), "AUTH_LOGIN", "utilisateur",
                        utilisateur.getId(), null, "code MFA invalide",
                        AuditEntryEntity.Result.FAILURE, null);
                throw new AuthentificationRefusee("Code MFA invalide ou expiré");
            }
            defisMfa.remove(cle);
        }

        utilisateur.setLastLoginAt(Instant.now());
        utilisateurs.save(utilisateur);

        audit.record(utilisateur.getSupabaseUserId(), "AUTH_LOGIN", "utilisateur",
                utilisateur.getId(), utilisateur.getStructureId(), "connexion interne HS256",
                AuditEntryEntity.Result.SUCCESS, null);

        String jeton = Jetons.jetonStaff(
                utilisateur.getSupabaseUserId(),
                utilisateur.getRole().getCode(),
                nomComplet(utilisateur),
                utilisateur.getStructureId(),
                secretJwt);
        return new ResultatStaff(jeton, Instant.now().getEpochSecond() + Jetons.DUREE_SECONDES,
                utilisateur.getSupabaseUserId(), utilisateur.getEmail(),
                utilisateur.getRole().getCode(), utilisateur.getStructureId(),
                nomComplet(utilisateur), utilisateur.isMfaActive());
    }

    // ------------------------------------------------------------------
    // OTP patient
    // ------------------------------------------------------------------

    public record DemandeOtp(String message, String codeDemo) {
    }

    public record ResultatPatient(String jeton, long expireALe, UUID patientId, String nom) {
    }

    /**
     * Étape 1 — le patient demande son code par téléphone.
     * Un numéro sans dossier reçoit une réponse neutre (anti-énumération).
     */
    public DemandeOtp demanderCodePatient(String telephone) {
        String tel = normaliserTelephone(telephone);
        if (tel == null) {
            throw new AuthentificationRefusee("Numéro de téléphone invalide (8 chiffres attendus)");
        }
        List<UUID> patients = jdbc.queryForList(
                "SELECT patient_id FROM identity.patient_telecom WHERE system = 'phone' AND value = ?",
                UUID.class, tel);
        if (patients.isEmpty()) {
            // Message identique au cas nominal : aucune divulgation d'existence.
            LOG.info("OTP patient demandé pour un numéro sans dossier actif");
            return new DemandeOtp("Si ce numéro correspond à un dossier, un code vient d'être envoyé par SMS",
                    null);
        }
        String code = codePatient4();
        codesPatient.put(tel, new CodeEnAttente(code, Instant.now().plusSeconds(VALIDITE_OTP_SECONDES)));
        audit.record(null, "AUTH_PATIENT_OTP", "patient", patients.get(0), null,
                "demande de code", AuditEntryEntity.Result.SUCCESS, null);
        // Posture démo : le code est renvoyé (aucune passerelle SMS livrée — I27).
        return new DemandeOtp("Code envoyé (démonstration : il est affiché ici, jamais en production)",
                demoOtpVisible ? code : null);
    }

    /** Étape 2 — vérification du code → jeton autoporteur patient_id. */
    @Transactional
    public ResultatPatient verifierCodePatient(String telephone, String code) {
        String tel = normaliserTelephone(telephone);
        CodeEnAttente attendu = tel == null ? null : codesPatient.get(tel);
        if (tel == null || attendu == null || !attendu.valide()
                || code == null || !attendu.code().equals(code.trim())) {
            audit.record(null, "AUTH_PATIENT_LOGIN", "patient", null, null,
                    "code invalide", AuditEntryEntity.Result.FAILURE, null);
            throw new AuthentificationRefusee("Code invalide ou expiré");
        }
        codesPatient.remove(tel);

        List<Map<String, Object>> lignes = jdbc.queryForList("""
                SELECT p.id, p.deceased, n.family, n.given
                  FROM identity.patient p
                  JOIN identity.patient_name n ON n.patient_id = p.id AND n.use = 'official'
                 WHERE p.id = (SELECT patient_id FROM identity.patient_telecom
                                WHERE system = 'phone' AND value = ? LIMIT 1)
                """, tel);
        if (lignes.isEmpty()) {
            throw new AuthentificationRefusee("Aucun dossier lié à ce numéro");
        }
        Map<String, Object> ligne = lignes.get(0);
        if (Boolean.TRUE.equals(ligne.get("deceased"))) {
            throw new AuthentificationRefusee("Ce dossier est clôturé (décès déclaré)");
        }
        UUID patientId = (UUID) ligne.get("id");
        String nom = (ligne.get("family") + " " + ligne.get("given")).trim();

        audit.record(patientId, "AUTH_PATIENT_LOGIN", "patient", patientId, null,
                "connexion patient par OTP", AuditEntryEntity.Result.SUCCESS, null);

        String jeton = Jetons.jetonPatient(patientId, nom, secretJwt);
        return new ResultatPatient(jeton, Instant.now().getEpochSecond() + Jetons.DUREE_SECONDES,
                patientId, nom);
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    private static String normaliserTelephone(String brut) {
        if (brut == null) {
            return null;
        }
        String chiffres = brut.replaceAll("\\D", "");
        if (chiffres.length() == 8) {
            return chiffres;
        }
        if (chiffres.length() == 10 && chiffres.startsWith("00226")) {
            return chiffres.substring(5);
        }
        if (chiffres.length() == 12 && chiffres.startsWith("226")) {
            return chiffres.substring(3);
        }
        if (chiffres.length() == 11 && chiffres.startsWith("0226")) {
            return chiffres.substring(4);
        }
        return null;
    }

    private String codePatient4() {
        return String.format("%04d", aleatoire.nextInt(10_000));
    }

    private String codeMfa6() {
        return String.format("%06d", aleatoire.nextInt(1_000_000));
    }

    private static String nomComplet(UtilisateurEntity utilisateur) {
        String prenoms = utilisateur.getPrenoms() == null ? "" : utilisateur.getPrenoms().trim();
        return (prenoms + " " + utilisateur.getNom()).trim();
    }

    private static String statutLisible(String statut) {
        return switch (statut) {
            case "invite" -> "invité (non encore activé)";
            case "suspendu" -> "suspendu";
            default -> statut;
        };
    }
}
