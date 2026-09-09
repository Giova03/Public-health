package bf.publichealth.modules.fhir.adapter.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Résolveur d'exceptions FHIR pour les requêtes {@code /fhir/**} qui
 * n'atteignent AUCUN contrôleur — la loi architecturale n°4 impose que TOUT
 * ce qui passe sous /fhir/ parle OperationOutcome, y compris les erreurs de
 * routage :
 * <ul>
 *   <li>POST/PUT/DELETE sur une route GET (interaction non supportée) →
 *       {@code 405} OperationOutcome {@code not-supported} — LA preuve que la
 *       façade est en lecture seule ;</li>
 *   <li>Accept incompatibles (la façade ne parle que application/fhir+json) →
 *       {@code 406} OperationOutcome {@code not-supported} ;</li>
 *   <li>chemin inconnu sous /fhir/ (ressource non livrée) →
 *       {@code 404} OperationOutcome {@code not-found}.</li>
 * </ul>
 *
 * <p>Pourquoi un HandlerExceptionResolver et pas seulement un advice : ces
 * exceptions sont levées pendant la résolution du handler (aucun contrôleur
 * n'est encore identifié) — un {@code @RestControllerAdvice(assignableTypes)}
 * ne peut pas s'appliquer, et le gestionnaire global problem+json de la
 * plateforme prendrait la main (405→500 incohérent pour un partenaire FHIR).
 * Ce résolveur, ordonné AVANT les résolveurs standards, ne mord QUE sur les
 * chemins /fhir/ : tout le reste de la plateforme est inchangé.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FhirErreurResolver implements HandlerExceptionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FhirErreurResolver.class);

    private final FhirEncodeur encodeur;

    public FhirErreurResolver(FhirEncodeur encodeur) {
        this.encodeur = encodeur;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest requete, HttpServletResponse reponse,
                                         Object handler, Exception exception) {
        if (!cheminFhir(requete)) {
            return null; // hors périmètre : la chaîne standard poursuit
        }
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            // Lecture seule : seule l'interaction GET est supportée. RFC 7231 :
            // le 405 porte l'en-tête Allow.
            reponse.setHeader("Allow", "GET");
            repondre(reponse, HttpStatus.METHOD_NOT_ALLOWED, OperationOutcome.IssueType.NOTSUPPORTED,
                    "Interaction non supportée : la façade /fhir/R4 est en LECTURE SEULE (GET) — "
                            + "les écritures passent par l'API /api/v1");
            return new ModelAndView();
        }
        if (exception instanceof HttpMediaTypeNotAcceptableException) {
            repondre(reponse, HttpStatus.NOT_ACCEPTABLE, OperationOutcome.IssueType.NOTSUPPORTED,
                    "Aucune représentation acceptable : la façade /fhir/R4 ne produit que "
                            + FhirEncodeur.FHIR_JSON + " (paramètre _format=json)");
            return new ModelAndView();
        }
        if (exception instanceof NoResourceFoundException) {
            repondre(reponse, HttpStatus.NOT_FOUND, OperationOutcome.IssueType.NOTFOUND,
                    "Ressource ou route FHIR inconnue : " + requete.getRequestURI()
                            + " — voir GET /fhir/R4/metadata pour les interactions livrées");
            return new ModelAndView();
        }
        // Toute autre exception (dont celles levées DANS un contrôleur) est
        // laissée à FhirOperationOutcomeAdvice / à la chaîne standard.
        return null;
    }

    private static boolean cheminFhir(HttpServletRequest requete) {
        String chemin = requete.getRequestURI().substring(requete.getContextPath().length());
        return chemin.equals("/fhir") || chemin.startsWith("/fhir/");
    }

    private void repondre(HttpServletResponse reponse, HttpStatus statut,
                          OperationOutcome.IssueType code, String diagnostics) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(code);
        issue.setDiagnostics(diagnostics);
        try {
            reponse.setStatus(statut.value());
            reponse.setContentType(FhirEncodeur.FHIR_JSON);
            reponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            reponse.getWriter().write(encodeur.json(outcome));
        } catch (IOException e) {
            // Le client est parti ou la réponse est déjà engagée : rien de plus
            // à faire, la connexion tombera d'elle-même.
            LOG.warn("Écriture du OperationOutcome impossible : {}", e.getMessage());
        }
    }
}
