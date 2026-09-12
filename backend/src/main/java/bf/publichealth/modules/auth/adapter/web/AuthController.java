package bf.publichealth.modules.auth.adapter.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.publichealth.modules.auth.application.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * API d'authentification — /api/v1/auth/** (PUBLIQUE : sans jeton).
 *
 * <p>Trois routes ouvertes, protégées par le rate-limit global
 * (600 req/60 s par IP — FiltreRateLimit) et auditées :</p>
 * <ul>
 *   <li>POST /api/v1/auth/login — staff : email + mot de passe (+ code MFA
 *       si requis ; la réponse 401 intermédiaire porte le défi) ;</li>
 *   <li>POST /api/v1/auth/patient/otp — patient : numéro → code ;</li>
 *   <li>POST /api/v1/auth/patient/verify — patient : code → jeton.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Staff : email + mot de passe (+ MFA) → jeton HS256 + profil. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest requete) {
        try {
            Object resultat = authService.login(requete.email(), requete.motDePasse(), requete.codeMfa());
            if (resultat instanceof AuthService.DefiMfa defi) {
                // 401 intermédiaire : le défi MFA est documenté dans le corps.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "mfaRequise", true,
                        "email", defi.email(),
                        "detail", "Code MFA requis — un code à 6 chiffres vient d'être généré"
                                + (defi.codeDemo() == null ? " et envoyé" : " (démonstration : " + defi.codeDemo() + ")"),
                        "codeDemo", defi.codeDemo() == null ? "" : defi.codeDemo()));
            }
            AuthService.ResultatStaff staff = (AuthService.ResultatStaff) resultat;
            return ResponseEntity.ok(Map.of(
                    "jeton", staff.jeton(),
                    "expireALe", staff.expireALe(),
                    "utilisateur", Map.of(
                            "id", staff.compteId(),
                            "email", staff.email(),
                            "role", staff.role(),
                            "structureId", staff.structureId() == null ? "" : staff.structureId().toString(),
                            "nom", staff.nom(),
                            "mfaActive", staff.mfaActive())));
        } catch (AuthService.AuthentificationRefusee e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
            problem.setTitle("Authentification refusée");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }
    }

    /** Patient, étape 1 : numéro de téléphone → code (renvoyé en démo). */
    @PostMapping("/patient/otp")
    public ResponseEntity<DemandeOtpResponse> demanderOtp(
            @Valid @RequestBody DemandeOtpRequest requete) {
        try {
            AuthService.DemandeOtp demande = authService.demanderCodePatient(requete.telephone());
            return ResponseEntity.accepted().body(new DemandeOtpResponse(
                    demande.message(), demande.codeDemo() == null ? "" : demande.codeDemo()));
        } catch (AuthService.AuthentificationRefusee e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Numéro invalide");
            return ResponseEntity.badRequest().body(new DemandeOtpResponse(e.getMessage(), ""));
        }
    }

    /** Patient, étape 2 : code → jeton autoporteur patient_id. */
    @PostMapping("/patient/verify")
    public ResponseEntity<?> verifierCode(@Valid @RequestBody VerificationRequest requete) {
        try {
            AuthService.ResultatPatient patient = authService.verifierCodePatient(
                    requete.telephone(), requete.code());
            return ResponseEntity.ok(Map.of(
                    "jeton", patient.jeton(),
                    "expireALe", patient.expireALe(),
                    "patientId", patient.patientId().toString(),
                    "nom", patient.nom()));
        } catch (AuthService.AuthentificationRefusee e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
            problem.setTitle("Code refusé");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record LoginRequest(
            @NotBlank(message = "L'email est obligatoire") String email,
            @NotBlank(message = "Le mot de passe est obligatoire") String motDePasse,
            String codeMfa) {
    }

    public record DemandeOtpRequest(
            @NotBlank(message = "Le numéro de téléphone est obligatoire") String telephone) {
    }

    public record DemandeOtpResponse(String message, String codeDemo) {
    }

    public record VerificationRequest(
            @NotBlank(message = "Le numéro de téléphone est obligatoire") String telephone,
            @NotBlank(message = "Le code est obligatoire") String code) {
    }
}
