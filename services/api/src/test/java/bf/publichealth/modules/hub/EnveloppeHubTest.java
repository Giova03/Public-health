package bf.publichealth.modules.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import bf.publichealth.modules.hub.domain.EnveloppeHub;
import bf.publichealth.modules.hub.domain.EvenementOutbox;
import bf.publichealth.modules.hub.domain.SignatureHmac;

/**
 * Enveloppe HUB — la CANONICITÉ est le contrat : mêmes données → mêmes
 * octets → même signature HMAC-SHA256 ; un seul champ bougé → signature
 * différente. Et la re-canonicalisation (après stockage jsonb) rend les
 * octets originaux : le destinataire peut toujours vérifier.
 */
@DisplayName("EnveloppeHub (canonicité et signature)")
class EnveloppeHubTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID EVENT_ID = UUID.fromString("018f23a1-0000-7000-8000-000000000001");
    private static final UUID AGREGAT = UUID.fromString("018f23a1-0000-7000-8000-000000000002");
    private static final Instant SURVENU = Instant.parse("2025-06-01T10:15:30Z");
    private static final Instant EMIS = Instant.parse("2025-06-01T10:16:00Z");
    private static final String CHARGE = "{\"patient\":\"018f23a1-0000-7000-8000-000000000002\",\"genre\":\"male\"}";

    private static final String SECRET = "secret-partage-de-test";

    private static EnveloppeHub enveloppe() {
        return new EnveloppeHub(EVENT_ID, 1L, "ph-hub-simulation", "sync.patient.created",
                AGREGAT, SURVENU, CHARGE, EMIS);
    }

    @Test
    @DisplayName("Canonicité : mêmes données → mêmes octets → même signature")
    void memesDonneesMemesOctets() {
        EnveloppeHub premiere = enveloppe();
        EnveloppeHub seconde = enveloppe();
        assertThat(premiere.octetsCanoniques()).isEqualTo(seconde.octetsCanoniques());
        assertThat(SignatureHmac.signer(premiere.octetsCanoniques(), SECRET))
                .isEqualTo(SignatureHmac.signer(seconde.octetsCanoniques(), SECRET));
    }

    @Test
    @DisplayName("Huit champs exactement, clés triées alphabétiquement")
    void huitChampsTries() throws Exception {
        JsonNode noeud = JSON.readTree(enveloppe().jsonCanonique());
        List<String> champs = new ArrayList<>();
        noeud.fieldNames().forEachRemaining(champs::add);
        assertThat(champs).containsExactly(EnveloppeHub.CHAMPS);
        // CHAMPS est l'ordre trié par construction.
        assertThat(champs).isSorted();
    }

    @Test
    @DisplayName("Sérialisation compacte : aucun espace dans le JSON canonique")
    void compacte() {
        assertThat(enveloppe().jsonCanonique()).doesNotContain(" ");
    }

    @Test
    @DisplayName("Le champ payload est inséré tel quel")
    void payloadInsereTelQuel() throws Exception {
        JsonNode noeud = JSON.readTree(enveloppe().jsonCanonique());
        assertThat(noeud.path("payload").path("patient").asText())
                .isEqualTo(AGREGAT.toString());
        assertThat(noeud.path("event_type").asText()).isEqualTo("sync.patient.created");
        assertThat(noeud.path("sequence").asLong()).isEqualTo(1L);
        assertThat(noeud.path("emis_a").asText()).isEqualTo("2025-06-01T10:16:00Z");
        assertThat(noeud.path("occurred_at").asText()).isEqualTo("2025-06-01T10:15:30Z");
    }

    @Test
    @DisplayName("Un champ bougé → octets différents → signature différente")
    void unChampBougeCasseLaSignature() {
        byte[] originaux = enveloppe().octetsCanoniques();
        String signatureOriginale = SignatureHmac.signer(originaux, SECRET);

        // La séquence bouge.
        EnveloppeHub sequenceBougee = new EnveloppeHub(EVENT_ID, 2L, "ph-hub-simulation",
                "sync.patient.created", AGREGAT, SURVENU, CHARGE, EMIS);
        assertThat(sequenceBougee.octetsCanoniques()).isNotEqualTo(originaux);
        assertThat(SignatureHmac.signer(sequenceBougee.octetsCanoniques(), SECRET))
                .isNotEqualTo(signatureOriginale);

        // Le type d'événement bouge.
        EnveloppeHub typeBouge = new EnveloppeHub(EVENT_ID, 1L, "ph-hub-simulation",
                "sync.encounter.created", AGREGAT, SURVENU, CHARGE, EMIS);
        assertThat(SignatureHmac.signer(typeBouge.octetsCanoniques(), SECRET))
                .isNotEqualTo(signatureOriginale);

        // La charge utile bouge (un caractère).
        EnveloppeHub chargeBougee = new EnveloppeHub(EVENT_ID, 1L, "ph-hub-simulation",
                "sync.patient.created", AGREGAT, SURVENU,
                "{\"patient\":\"" + AGREGAT + "\",\"genre\":\"female\"}", EMIS);
        assertThat(SignatureHmac.signer(chargeBougee.octetsCanoniques(), SECRET))
                .isNotEqualTo(signatureOriginale);

        // La destination bouge.
        EnveloppeHub destinationBougee = new EnveloppeHub(EVENT_ID, 1L, "ph-hub-national",
                "sync.patient.created", AGREGAT, SURVENU, CHARGE, EMIS);
        assertThat(SignatureHmac.signer(destinationBougee.octetsCanoniques(), SECRET))
                .isNotEqualTo(signatureOriginale);

        // L'émission bouge (une seconde).
        EnveloppeHub emissionBougee = new EnveloppeHub(EVENT_ID, 1L, "ph-hub-simulation",
                "sync.patient.created", AGREGAT, SURVENU, CHARGE, EMIS.plusSeconds(1));
        assertThat(SignatureHmac.signer(emissionBougee.octetsCanoniques(), SECRET))
                .isNotEqualTo(signatureOriginale);
    }

    @Test
    @DisplayName("Secret différent → signature différente (et vérification croisée)")
    void secretDifferentSignatureDifferent() {
        byte[] octets = enveloppe().octetsCanoniques();
        String signature = SignatureHmac.signer(octets, SECRET);
        String autre = SignatureHmac.signer(octets, "autre-secret");
        assertThat(autre).isNotEqualTo(signature);
        assertThat(SignatureHmac.verifie(octets, SECRET, signature)).isTrue();
        assertThat(SignatureHmac.verifie(octets, "autre-secret", signature)).isFalse();
        assertThat(SignatureHmac.verifie(octets, SECRET, null)).isFalse();
        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("Re-canonicalisation (après stockage jsonb) : octets originaux restitués")
    void reCanonicalisationRestitueLesOctets() {
        byte[] originaux = enveloppe().octetsCanoniques();

        // Le stockage jsonb restitue les clés dans SON ordre : simulons une
        // forme quelconque (clés mélangées) de la MÊME enveloppe.
        String formeBougee = formeReordonnee(enveloppe().jsonCanonique());
        assertThat(formeBougee).isNotEqualTo(enveloppe().jsonCanonique());

        byte[] reCanoniqees = EnveloppeHub.octetsCanoniques(formeBougee);
        assertThat(reCanoniqees).isEqualTo(originaux);
        // La signature porte bien sur les octets originaux.
        assertThat(SignatureHmac.verifie(reCanoniqees, SECRET,
                SignatureHmac.signer(originaux, SECRET))).isTrue();
    }

    @Test
    @DisplayName("Round-trip : octetsCanoniques(jsonCanonique()) == octetsCanoniques()")
    void roundTrip() {
        EnveloppeHub enveloppe = enveloppe();
        assertThat(EnveloppeHub.octetsCanoniques(enveloppe.jsonCanonique()))
                .isEqualTo(enveloppe.octetsCanoniques());
    }

    @Test
    @DisplayName("Usine depuis un événement outbox : champs reportés, séquence posée")
    void usineDepuisOutbox() {
        EvenementOutbox evenement = new EvenementOutbox(EVENT_ID, "sync.encounter.created",
                AGREGAT, CHARGE, SURVENU);
        EnveloppeHub enveloppe = EnveloppeHub.de(evenement, "ph-hub-national", 5L, EMIS);
        JsonNode noeud;
        try {
            noeud = JSON.readTree(enveloppe.jsonCanonique());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(noeud.path("sequence").asLong()).isEqualTo(5L);
        assertThat(noeud.path("destination").asText()).isEqualTo("ph-hub-national");
        assertThat(noeud.path("event_id").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(noeud.path("aggregate_id").asText()).isEqualTo(AGREGAT.toString());
    }

    @Test
    @DisplayName("Gardes : séquence positive, champs obligatoires, charge JSON valide")
    void gardes() {
        assertThatThrownBy(() -> new EnveloppeHub(EVENT_ID, 0L, "ph-hub-simulation",
                "sync.patient.created", AGREGAT, SURVENU, CHARGE, EMIS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictement positive");
        assertThatThrownBy(() -> new EnveloppeHub(EVENT_ID, 1L, null,
                "sync.patient.created", AGREGAT, SURVENU, CHARGE, EMIS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EnveloppeHub(EVENT_ID, 1L, "ph-hub-simulation",
                "sync.patient.created", AGREGAT, SURVENU, "{pas du json", EMIS)
                .octetsCanoniques())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illisible");
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    /** Re-sérialise l'objet JSON avec les clés mélangées (ordre inversé). */
    private static String formeReordonnee(String json) {
        try {
            ObjectNode source = (ObjectNode) JSON.readTree(json);
            List<String> champs = new ArrayList<>();
            source.fieldNames().forEachRemaining(champs::add);
            ObjectNode bougee = JSON.createObjectNode();
            for (int i = champs.size() - 1; i >= 0; i--) {
                bougee.set(champs.get(i), source.get(champs.get(i)));
            }
            return JSON.writeValueAsString(bougee);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
