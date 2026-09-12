package bf.publichealth.config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import bf.publichealth.modules.administration.domain.RoleUtilisateur;
import bf.publichealth.modules.administration.domain.RolesPermissions;
import bf.publichealth.modules.audit.adapter.persistence.AuditEntryEntity;
import bf.publichealth.modules.audit.application.AuditRecorder;
import bf.publichealth.modules.identity.application.PatientService;

/**
 * INTERCEPTEUR RBAC — correction de l'insuffisance I2 (matrice décorative).
 *
 * <p>Chaque route {@code /api/v1/**} (et {@code /fhir/**}) exige désormais
 * SA permission de la matrice {@link RolesPermissions} : le rôle du jeton
 * (claim {@code app_role}) est résolu, ses permissions vérifiées, le refus
 * est 403 problem+json + entrée d'audit DENIED. Plus aucune route métier
 * n'est accessible avec un rôle qui ne porte pas la permission.</p>
 *
 * <p>Exceptions ouvertes (aucune permission exigée) :</p>
 * <ul>
 *   <li>{@code /api/v1/auth/**} — c'est la porte d'entrée elle-même
 *       (rate-limitée et auditée) ;</li>
 *   <li>{@code /api/v1/webhooks/fedapay} — intégrité HMAC, pas de jeton ;</li>
 *   <li>{@code /api/v1/meta} et {@code /api/v1/sync/**} — authentifiés
 *       (jeton de service / staff) mais sans permission unitaire :
 *       l'uplink rejoue des opérations déjà autorisées à l'émission ;</li>
 *   <li>{@code GET /api/v1/organizations} — annuaire national, lecture
 *       pour tout agent authentifié (écriture : admin:gerer).</li>
 * </ul>
 *
 * <p>Rôle {@code patient} (jeton autoporteur patient_id, émis par
 * /api/v1/auth/patient/verify) : accès UNIQUEMENT à SES données —
 * son dossier, ses consultations, ses ordonnances, ses examens et
 * SES rendez-vous (self-service). Toute autre route → 403. Le
 * périmètre « patient » est vérifié ICI (id de chemin / patientId
 * d'interrogation = claim patient_id), complété par les services
 * pour les corps de requête (RDV : patient_id forcé au claim).</p>
 *
 * <p>Ce filtre ne fait RIEN quand {@code securite.jwt.actif=false}
 * (posture Sprint 0 des tests) : la posture déployée (true) est la
 * seule qui compte en production.</p>
 */
@Component
public class FiltrePermissions extends OncePerRequestFilter implements Ordered {

    /** Une règle : (méthode, motif, permission exigée). */
    private record Regle(String methode, String motif, String permission) {
    }

    /** Routes autoportées du patient (documentation — règles vives dans perimetrePatientValide). */
    private static final List<String> ROUTES_PATIENT = List.of();

    private static final List<Regle> REGLES = List.of(
            // --- identité / MPI
            new Regle("GET", "/api/v1/patients", RolesPermissions.PATIENT_LIRE),
            new Regle("POST", "/api/v1/patients", RolesPermissions.PATIENT_ECRIRE),
            new Regle("GET", "/api/v1/patients/*", RolesPermissions.PATIENT_LIRE),
            new Regle("POST", "/api/v1/patients/*/deces", RolesPermissions.CONSULTATION_ECRIRE),
            new Regle("POST", "/api/v1/identity/matches", RolesPermissions.PATIENT_ECRIRE),
            new Regle("GET", "/api/v1/identity/matches", RolesPermissions.ADMIN_GERER),
            new Regle("POST", "/api/v1/identity/matches/*/review", RolesPermissions.ADMIN_GERER),
            new Regle("GET", "/api/v1/identity/merge-log", RolesPermissions.AUDIT_LIRE),
            // --- consultations (I4 : l'acte clinique persisté)
            new Regle("GET", "/api/v1/consultations", RolesPermissions.CONSULTATION_LIRE),
            new Regle("POST", "/api/v1/consultations", RolesPermissions.CONSULTATION_ECRIRE),
            new Regle("GET", "/api/v1/consultations/*", RolesPermissions.CONSULTATION_LIRE),
            // --- ordonnances
            new Regle("GET", "/api/v1/prescriptions", RolesPermissions.PRESCRIPTION_LIRE),
            new Regle("POST", "/api/v1/prescriptions", RolesPermissions.PRESCRIPTION_ECRIRE),
            new Regle("GET", "/api/v1/prescriptions/*", RolesPermissions.PRESCRIPTION_LIRE),
            new Regle("POST", "/api/v1/prescriptions/*/cancel", RolesPermissions.PRESCRIPTION_ECRIRE),
            new Regle("POST", "/api/v1/prescriptions/*/entered-in-error", RolesPermissions.PRESCRIPTION_ECRIRE),
            new Regle("POST", "/api/v1/prescriptions/*/items/*/dispense", RolesPermissions.DISPENSER),
            // --- laboratoire (I6)
            new Regle("GET", "/api/v1/examens", RolesPermissions.CONSULTATION_LIRE),
            new Regle("POST", "/api/v1/examens", RolesPermissions.LABORATOIRE_ECRIRE),
            new Regle("GET", "/api/v1/examens/*", RolesPermissions.CONSULTATION_LIRE),
            new Regle("POST", "/api/v1/examens/*/resultat", RolesPermissions.LABORATOIRE_ECRIRE),
            // --- rendez-vous (I10 : RDV passé immuable — règle au service)
            new Regle("GET", "/api/v1/appointments", RolesPermissions.RENDEZVOUS_GERER),
            new Regle("POST", "/api/v1/appointments", RolesPermissions.RENDEZVOUS_GERER),
            new Regle("GET", "/api/v1/appointments/*", RolesPermissions.RENDEZVOUS_GERER),
            new Regle("POST", "/api/v1/appointments/*/confirmer", RolesPermissions.RENDEZVOUS_GERER),
            new Regle("POST", "/api/v1/appointments/*/honorer", RolesPermissions.RENDEZVOUS_GERER),
            new Regle("POST", "/api/v1/appointments/*/annuler", RolesPermissions.RENDEZVOUS_GERER),
            // --- stock (I8)
            new Regle("GET", "/api/v1/stock", RolesPermissions.STOCK_GERER),
            new Regle("GET", "/api/v1/stock/*", RolesPermissions.STOCK_GERER),
            new Regle("POST", "/api/v1/stock/mouvements", RolesPermissions.STOCK_GERER),
            // --- référence / contre-référence (I7)
            new Regle("GET", "/api/v1/references", RolesPermissions.REFERENCE_GERER),
            new Regle("POST", "/api/v1/references", RolesPermissions.REFERENCE_GERER),
            new Regle("GET", "/api/v1/references/*", RolesPermissions.REFERENCE_GERER),
            new Regle("POST", "/api/v1/references/*/reception", RolesPermissions.REFERENCE_GERER),
            new Regle("POST", "/api/v1/references/*/hospitalisation", RolesPermissions.REFERENCE_GERER),
            new Regle("POST", "/api/v1/references/*/contre-reference", RolesPermissions.REFERENCE_GERER),
            // --- paiements / facturation
            new Regle("POST", "/api/v1/payments", RolesPermissions.PAIEMENT_INITIER),
            new Regle("GET", "/api/v1/payments/*", RolesPermissions.PAIEMENT_LIRE),
            new Regle("POST", "/api/v1/invoices", RolesPermissions.PAIEMENT_INITIER),
            new Regle("GET", "/api/v1/invoices", RolesPermissions.PAIEMENT_LIRE),
            new Regle("GET", "/api/v1/invoices/*", RolesPermissions.PAIEMENT_LIRE),
            new Regle("POST", "/api/v1/invoices/*/issue", RolesPermissions.PAIEMENT_INITIER),
            new Regle("POST", "/api/v1/invoices/*/void", RolesPermissions.PAIEMENT_INITIER),
            new Regle("POST", "/api/v1/reconciliation/run", RolesPermissions.PAIEMENT_RECONCILIER),
            new Regle("GET", "/api/v1/reconciliation/runs", RolesPermissions.PAIEMENT_RECONCILIER),
            // --- audit / supervision (I16 : écran d'audit)
            new Regle("GET", "/api/v1/audit/entries", RolesPermissions.AUDIT_LIRE),
            new Regle("POST", "/api/v1/audit/break-the-glass", RolesPermissions.PATIENT_LIRE),
            new Regle("GET", "/api/v1/audit/emergency-access", RolesPermissions.AUDIT_LIRE),
            new Regle("POST", "/api/v1/audit/emergency-access/*/review", RolesPermissions.AUDIT_LIRE),
            // --- statistiques SNIS (I11)
            new Regle("GET", "/api/v1/statistiques/**", RolesPermissions.AUDIT_LIRE),
            // --- back-office
            new Regle("ANY", "/api/v1/admin/**", RolesPermissions.ADMIN_GERER),
            new Regle("POST", "/api/v1/organizations", RolesPermissions.ADMIN_GERER),
            new Regle("PATCH", "/api/v1/organizations/*", RolesPermissions.ADMIN_GERER),
            new Regle("POST", "/api/v1/organizations/*/activate", RolesPermissions.ADMIN_GERER),
            new Regle("POST", "/api/v1/organizations/*/deactivate", RolesPermissions.ADMIN_GERER),
            // --- HUB interop : administrateur
            new Regle("ANY", "/api/v1/hub/**", RolesPermissions.ADMIN_GERER),
            // --- FHIR : lecture patient
            new Regle("GET", "/fhir/**", RolesPermissions.PATIENT_LIRE));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final boolean jwtActif;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;

    public FiltrePermissions(
            @Value("${securite.jwt.actif:false}") boolean jwtActif,
            ObjectMapper objectMapper,
            AuditRecorder audit) {
        this.jwtActif = jwtActif;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public int getOrder() {
        // Après la chaîne Spring Security (ordre -100 par défaut) et après
        // le rate-limit : on ne vérifie les permissions que d'un appelant
        // authentifié et non saturé.
        return 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse,
                                    FilterChain chaine) throws ServletException, IOException {
        if (!jwtActif || !doitVerifier(requete)) {
            chaine.doFilter(requete, reponse);
            return;
        }

        Authentication authentification = SecurityContextHolder.getContext().getAuthentication();
        if (authentification == null || !authentification.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(authentification.getPrincipal()))) {
            // La couche sécurité a déjà répondu 401 — ceinture et bretelles.
            chaine.doFilter(requete, reponse);
            return;
        }

        String role = roleCourant(authentification);
        String methode = requete.getMethod();
        String chemin = requete.getRequestURI();

        // ---- Rôle patient : périmètre STRICTEMENT personnel
        if (RoleUtilisateur.ROLE_PATIENT.equals(role)) {
            if (!perimetrePatientValide(requete, chemin, authentification)) {
                refuser(requete, reponse, authentification, chemin,
                        "Un patient n'accède qu'à ses propres données");
                return;
            }
            chaine.doFilter(requete, reponse);
            return;
        }

        // ---- Rôles staff : permission exigée par la route
        String permission = permissionExigee(methode, chemin);
        if (permission == null) {
            // Route sans règle : authentifiée sans permission unitaire
            // (meta, sync, organizations en lecture…) — la liste est close
            // par doitVerifier + ce commentaire : TOUT ajout de route DOIT
            // venir avec sa règle.
            chaine.doFilter(requete, reponse);
            return;
        }
        RoleUtilisateur roleUtilisateur;
        try {
            roleUtilisateur = RoleUtilisateur.depuisCode(role);
        } catch (IllegalArgumentException e) {
            refuser(requete, reponse, authentification, chemin, "Rôle inconnu : " + role);
            return;
        }
        if (!RolesPermissions.permissionsDe(roleUtilisateur).contains(permission)) {
            refuser(requete, reponse, authentification, chemin,
                    "La permission " + permission + " est requise (rôle " + role + ")");
            return;
        }
        chaine.doFilter(requete, reponse);
    }

    // ------------------------------------------------------------------
    // Résolution
    // ------------------------------------------------------------------

    private static boolean doitVerifier(HttpServletRequest requete) {
        String chemin = requete.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(requete.getMethod())) {
            return false;
        }
        if (chemin.startsWith("/api/v1/auth/") || chemin.equals("/api/v1/webhooks/fedapay")) {
            return false;
        }
        return chemin.startsWith("/api/v1/") || chemin.startsWith("/fhir/");
    }

    private static String roleCourant(Authentication authentification) {
        for (GrantedAuthority autorite : authentification.getAuthorities()) {
            String nom = autorite.getAuthority();
            if (nom != null && nom.startsWith("ROLE_")) {
                return nom.substring("ROLE_".length()).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String permissionExigee(String methode, String chemin) {
        for (Regle regle : REGLES) {
            if (("ANY".equals(regle.methode()) || regle.methode().equalsIgnoreCase(methode))
                    && MATCHER.match(regle.motif(), chemin)) {
                return regle.permission();
            }
        }
        return null;
    }

    /** Périmètre patient : le chemin interroge SES données, rien d'autre. */
    private static boolean perimetrePatientValide(HttpServletRequest requete, String chemin,
                                                  Authentication authentification) {
        UUID patientId = claimPatientId(authentification);
        if (patientId == null) {
            return false;
        }
        String methode = requete.getMethod().toUpperCase(Locale.ROOT);

        // GET uniquement : SES lectures (dossier, consultations, ordonnances,
        // examens). AUCUNE écriture clinique pour un patient.
        if ("GET".equals(methode)) {
            boolean lectureAutorisee = List.of(
                    "/api/v1/patients/*",
                    "/api/v1/consultations",
                    "/api/v1/consultations/*",
                    "/api/v1/prescriptions",
                    "/api/v1/prescriptions/*",
                    "/api/v1/examens",
                    "/api/v1/examens/*",
                    "/api/v1/appointments",
                    "/api/v1/appointments/*").stream().anyMatch(motif -> MATCHER.match(motif, chemin));
            if (!lectureAutorisee) {
                return false;
            }
        } else if ("POST".equals(methode)) {
            // Self-service RDV UNIQUEMENT : créer (patient_id forcé au claim)
            // et annuler le SIEN (propriété vérifiée par le service).
            boolean ecritureAutorisee = chemin.equals("/api/v1/appointments")
                    || chemin.endsWith("/annuler") && chemin.startsWith("/api/v1/appointments/");
            if (!ecritureAutorisee) {
                return false;
            }
        } else {
            return false;
        }

        // GET/POST /api/v1/patients/{id} : l'id doit être le sien.
        if (chemin.startsWith("/api/v1/patients/")) {
            String id = dernierSegment(chemin);
            return patientId.toString().equals(id);
        }
        // Listes : le paramètre patientId, s'il est fourni, doit être le sien.
        String paramPatient = requete.getParameter("patientId");
        if (paramPatient != null && !paramPatient.isBlank()) {
            return patientId.toString().equals(paramPatient.trim());
        }
        return true;
    }

    private static String dernierSegment(String chemin) {
        String[] segments = chemin.split("/");
        return segments[segments.length - 1];
    }

    private static UUID claimPatientId(Authentication authentification) {
        if (authentification instanceof JwtAuthenticationToken jeton
                && jeton.getToken().getClaims().get("patient_id") instanceof String claim) {
            try {
                return UUID.fromString(claim);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Refus : 403 problem+json + audit DENIED (I16)
    // ------------------------------------------------------------------

    private void refuser(HttpServletRequest requete, HttpServletResponse reponse,
                         Authentication authentification, String chemin, String detail)
            throws IOException {
        UUID acteur = null;
        if (authentification instanceof JwtAuthenticationToken jeton) {
            Jwt jet = jeton.getToken();
            if (jet.getSubject() != null) {
                try {
                    acteur = UUID.fromString(jet.getSubject());
                } catch (IllegalArgumentException ignore) {
                    // sub non UUID — acteur anonyme
                }
            }
        }
        audit.record(acteur, "PERMISSION_DENIED", "api", null, null,
                requete.getMethod() + " " + chemin + " : " + detail,
                AuditEntryEntity.Result.DENIED, null);
        ReponsesProblemDetail.ecrire(reponse, HttpStatus.FORBIDDEN,
                "Accès refusé", detail, objectMapper);
    }
}
