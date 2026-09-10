package bf.publichealth.modules.fhir.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port de lecture du MPI (schéma identity) pour la façade FHIR — miroir
 * descendant en lecture seule, patron {@code MiroirPatientsPg} : SELECT
 * mono-schéma, noms/télécoms/identifiants ramenés en requêtes plates, aucun
 * JOIN inter-schémas.
 */
public interface LecturePatientsFhir {

    /** Dossier par identifiant interne — le dossier fusionné EST retourné
     *  (masterId non null) : c'est la façade qui décide 404/410/200. */
    Optional<PatientFhir> parId(UUID id);

    /** Recherche miroir : les dossiers fusionnés et inactifs n'existent pas. */
    ResultatRecherche<PatientFhir> rechercher(CriteresPatient criteres, int limite, int decalage);

    /** Résultat paginé : éléments de la page + total général (pour Bundle.total). */
    record ResultatRecherche<T>(List<T> elements, long total) {
    }

    /**
     * Critères de recherche Patient — miroir des critères identity (E1) :
     * patronyme par préfixe (insensible à la casse), téléphone exact,
     * naissance exacte, identifiant exact. {@code systemeIdentifiant} vaut
     * {@code PH} (ph_reference) ou un code identity ({@code NUNP},
     * {@code CNIB}, {@code ANCIEN_REGISTRE}) ; null = valeur seule
     * (ph_reference OU n'importe quel identifiant).
     */
    record CriteresPatient(String family, String given, String phone, LocalDate birthDate,
                           String systemeIdentifiant, String valeurIdentifiant) {
    }

    /** Lecture d'un dossier patient — projection plate du schéma identity. */
    record PatientFhir(UUID id, String phReference, boolean active, UUID masterId, String gender,
                       LocalDate birthDate, boolean birthDateApproximative,
                       Instant createdAt, Instant updatedAt,
                       List<NomFhir> noms, List<TelecomFhir> telecoms,
                       List<IdentifiantFhir> identifiants) {
    }

    /** Nom : use ∈ {official, usual} (CHECK V1), given = prénoms en un texte. */
    record NomFhir(String use, String family, String given) {
    }

    /** Télécom : system ∈ {phone, email} (CHECK V1), use libre. */
    record TelecomFhir(String system, String value, String use) {
    }

    /** Identifiant national : system ∈ {NUNP, CNIB, ANCIEN_REGISTRE, LOCAL}. */
    record IdentifiantFhir(String system, String value) {
    }
}
