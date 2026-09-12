package bf.publichealth.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Écriture de réponses d'erreur au format problem+json (RFC 7807) depuis
 * les filtres et points d'entrée de sécurité — mêmes champs que le
 * GlobalExceptionHandler (type/title/status/detail), sans pile interne.
 */
public final class ReponsesProblemDetail {

    private static final Logger LOG = LoggerFactory.getLogger(ReponsesProblemDetail.class);

    private ReponsesProblemDetail() {
    }

    /** Écrit un ProblemDetail ; n'échoue jamais sur une réponse déjà close. */
    public static void ecrire(HttpServletResponse reponse, HttpStatus statut,
                              String titre, String detail, ObjectMapper objectMapper) {
        try {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(statut, detail);
            problem.setTitle(titre);
            reponse.setStatus(statut.value());
            // Octets UTF-8 directs sur le flux : Content-Type reste
            // « application/problem+json » SANS suffixe charset (l'UTF-8 est
            // l'encodage par défaut du JSON — RFC 8259) et les accents du
            // titre ne se miment pas en ISO-8859-1 (bug observé :
            // « Accès refusé » → « Acc?s refus? »).
            reponse.setContentType("application/problem+json");
            reponse.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
        } catch (IOException e) {
            LOG.error("Écriture de la réponse problem+json impossible (statut {}) : {}",
                    statut.value(), e.getMessage());
        }
    }
}
