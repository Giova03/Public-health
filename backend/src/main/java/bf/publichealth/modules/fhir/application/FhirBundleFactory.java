package bf.publichealth.modules.fhir.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntrySearchComponent;
import org.hl7.fhir.r4.model.Bundle.BundleLinkComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Component;

/**
 * Fabrique des Bundles searchset de la façade — style FHIR correct :
 * self link TOUJOURS, total juste, fullUrl absolue par entrée,
 * mode "match" sur chaque entry.search, lien next uniquement
 * lorsqu'une page suivante existe.
 *
 * <p>Pagination par offset : le lien {@code next} porte
 * {@code page[offset]} = offset courant + _count. Les paramètres du self
 * link sont canoniques (ordre stable, valeurs encodées) — ils reflètent la
 * requête TELLE QUE TRAITÉE (valeurs par défaut _count=50 / page[offset]=0
 * matérialisées).</p>
 */
@Component
public class FhirBundleFactory {

    /**
     * @param baseUrl   base absolue de la façade (…/fhir/R4), issue de la requête HTTP
     * @param chemin    chemin de la ressource ("/Patient", "/Encounter"…)
     * @param parametres paramètres canoniques de la recherche (ordre d'insertion conservé)
     * @param ressources ressources de la page, déjà converties en R4
     * @param total     nombre TOTAL de résultats (pas seulement la page)
     * @param page      pagination effective
     */
    public Bundle searchset(String baseUrl, String chemin, Map<String, String> parametres,
                            List<? extends Resource> ressources, long total, FhirPagination page) {
        Bundle bundle = new Bundle();
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTotal((int) total);

        Map<String, String> parametresSelf = new LinkedHashMap<>(parametres);
        parametresSelf.put("_count", String.valueOf(page.count()));
        parametresSelf.put("page[offset]", String.valueOf(page.decalage()));
        bundle.addLink(new BundleLinkComponent()
                .setRelation("self")
                .setUrl(baseUrl + chemin + "?" + requete(parametresSelf)));

        if (page.decalage() + (long) page.count() < total) {
            Map<String, String> parametresNext = new LinkedHashMap<>(parametresSelf);
            parametresNext.put("page[offset]", String.valueOf(page.decalage() + page.count()));
            bundle.addLink(new BundleLinkComponent()
                    .setRelation("next")
                    .setUrl(baseUrl + chemin + "?" + requete(parametresNext)));
        }

        for (Resource ressource : ressources) {
            BundleEntryComponent entree = new BundleEntryComponent();
            entree.setFullUrl(baseUrl + "/" + ressource.fhirType()
                    + "/" + ressource.getIdElement().getIdPart());
            entree.setResource(ressource);
            entree.setSearch(new BundleEntrySearchComponent().setMode(SearchEntryMode.MATCH));
            bundle.addEntry(entree);
        }
        return bundle;
    }

    private static String requete(Map<String, String> parametres) {
        StringBuilder query = new StringBuilder();
        parametres.forEach((nom, valeur) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(nom)).append('=').append(encode(valeur));
        });
        return query.toString();
    }

    private static String encode(String valeur) {
        return URLEncoder.encode(valeur, StandardCharsets.UTF_8);
    }
}
