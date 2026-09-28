package io.loyaltyhub.common.privacy;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regole condivise dell'anonimizzazione (F-MBR-05, M7.5). Da Q-404 la sostituzione dei valori noti è per parole intere
 * e salta i valori sicuri: i casi di regressione sono qui, sull'API che usano engagement e ingestion; le regole di
 * dettaglio sono in {@link PersonalTextScrubberTest}.
 */
class PersonalDataTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LhEvent<JsonNode> fact(String type, String json) {
        return new LhEvent<>("1.0", "e1", "urn:loyaltyhub:service:member", type, "member:MBR-000003", null,
                null, null, null, null, null, 0, null, mapper.readTree(json));
    }

    @Test
    void recognisesTheAnonymizationFacts() {
        assertThat(PersonalData.isAnonymization(fact(LhEventTypes.Fact.MEMBER_STATUS_CHANGED,
                "{\"previousStatus\":\"ACTIVE\",\"newStatus\":\"ANONYMIZED\"}"))).isTrue();
        assertThat(PersonalData.isAnonymization(fact(LhEventTypes.Fact.MEMBER_UPDATED,
                "{\"memberId\":\"MBR-000003\",\"status\":\"ANONYMIZED\"}"))).isTrue();
        assertThat(PersonalData.isAnonymization(fact(LhEventTypes.Fact.MEMBER_STATUS_CHANGED,
                "{\"previousStatus\":\"ACTIVE\",\"newStatus\":\"BLOCKED\"}"))).isFalse();
        assertThat(PersonalData.isAnonymization(fact(LhEventTypes.Fact.MEMBER_UPDATED,
                "{\"memberId\":\"MBR-000003\",\"status\":\"ACTIVE\"}"))).isFalse();
        assertThat(PersonalData.isAnonymization(fact(LhEventTypes.Fact.WALLET_POINTS_EARNED,
                "{\"status\":\"ANONYMIZED\"}"))).isFalse();
    }

    @Test
    void redactRemovesPersonalKeysAtEveryDepthAndKeepsTheRest() {
        JsonNode in = mapper.readTree("""
                {"id":"e1","subject":"member:MBR-000003","data":{"memberId":"MBR-000003","firstName":"Giulia",
                 "lastName":"Ferri","email":"giulia.ferri@example.org","status":"ACTIVE","tier":"SILVER",
                 "attributes":{"householdSize":3},"labels":["vip"],
                 "items":[{"sku":"A1","shipping":{"name":"Giulia Ferri","city":"Bologna"}}]}}""");
        JsonNode out = PersonalData.redact(in);

        assertThat(out.toString()).doesNotContain("Giulia").doesNotContain("Ferri").doesNotContain("example.org")
                .doesNotContain("Bologna").doesNotContain("householdSize");
        assertThat(out.path("data").path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(out.path("data").path("tier").asString()).isEqualTo("SILVER");
        assertThat(out.path("data").path("items").get(0).path("sku").asString()).isEqualTo("A1");
        assertThat(in.path("data").path("firstName").asString()).as("l'originale non cambia").isEqualTo("Giulia");
    }

    @Test
    void scrubReplacesKnownValuesInFreeTextIgnoringCase() {
        List<String> tokens = PersonalData.nameTokens("Giulia", "Ferri", "giulia.ferri@example.org");
        assertThat(tokens.getFirst()).as("il valore più lungo per primo").isEqualTo("giulia.ferri@example.org");
        assertThat(PersonalData.scrub("Creato membro Giulia Ferri (GIULIA.FERRI@example.org)", tokens))
                .isEqualTo("Creato membro Membro anonimo (Membro anonimo)");
        assertThat(PersonalData.scrub("Ciao giulia, benvenuta", tokens)).isEqualTo("Ciao Membro anonimo, benvenuta");
        assertThat(PersonalData.scrub(null, tokens)).isNull();
    }

    @Test
    void tokensSkipBlanksShortValuesAndThePlaceholder() {
        assertThat(PersonalData.nameTokens("Al", null, "", "Membro anonimo")).isEmpty();
        assertThat(PersonalData.nameTokens("Anna", "Re")).containsExactly("Anna Re", "Anna");
    }

    @Test
    void redactAndScrubAlsoCleansValuesUnderOtherKeys() {
        JsonNode in = mapper.readTree("{\"summary\":\"Nota per Giulia Ferri\",\"email\":\"x@example.org\",\"n\":3}");
        JsonNode out = PersonalData.redactAndScrub(in, PersonalData.nameTokens("Giulia", "Ferri"));
        assertThat(out.path("summary").asString()).isEqualTo("Nota per Membro anonimo");
        assertThat(out.has("email")).isFalse();
        assertThat(out.path("n").asInt()).isEqualTo(3);

        JsonNode kept = PersonalData.scrubAll(in, PersonalData.nameTokens("Giulia", "Ferri"));
        assertThat(kept.has("email")).as("scrubAll non toglie chiavi").isTrue();
        assertThat(kept.path("summary").asString()).isEqualTo("Nota per Membro anonimo");
    }

    // ---------- Q-404: parole intere, valori sicuri conservati ----------

    @Test
    @DisplayName("Q-404: un valore dentro una parola più lunga o un codice non si tocca, la parola intera sì")
    void scrubReplacesWholeWordsOnly() {
        assertThat(PersonalData.scrub("Ada ha scritto ad Adamo: codice ADA7, premio RWD-ADAMO, NADA", List.of("Ada")))
                .isEqualTo("Membro anonimo ha scritto ad Adamo: codice ADA7, premio RWD-ADAMO, NADA");
        assertThat(PersonalData.scrub("Stato ANONYMIZED, prima anonimo", List.of("Anon")))
                .isEqualTo("Stato ANONYMIZED, prima anonimo");
        assertThat(PersonalData.scrub("Zed Zedda, cliente di Zed.", List.of("Zed")))
                .isEqualTo("Membro anonimo Zedda, cliente di Membro anonimo.");
        assertThat(PersonalData.scrub("Scritto a ada@example.test, non a leada@example.test",
                List.of("ada@example.test"))).isEqualTo("Scritto a Membro anonimo, non a leada@example.test");
        assertThat(PersonalData.scrub("Cliente CRM-10, ordine CRM-101", List.of("CRM-10")))
                .isEqualTo("Cliente Membro anonimo, ordine CRM-101");
    }

    @Test
    @DisplayName("Q-404: maiuscole e accenti in forma NFC, qualunque sia la forma del testo e del valore")
    void scrubIgnoresCaseAndNormalizesToNfc() {
        String nfd = Normalizer.normalize("Niccolò e NICCOLÒ, non Niccolòa", Normalizer.Form.NFD);
        assertThat(PersonalData.scrub(nfd, List.of("niccolò")))
                .isEqualTo("Membro anonimo e Membro anonimo, non Niccolòa");
        assertThat(PersonalData.scrub("Ciao Niccolò!", List.of(Normalizer.normalize("Niccolò", Normalizer.Form.NFD))))
                .isEqualTo("Ciao Membro anonimo!");
        assertThat(PersonalData.scrub("Ciao Niccolo", List.of("Niccolò"))).as("senza accento è un'altra parola")
                .isEqualTo("Ciao Niccolo");
    }

    @Test
    @DisplayName("Q-404: valori con caratteri speciali delle espressioni regolari presi alla lettera")
    void scrubQuotesRegexCharacters() {
        assertThat(PersonalData.scrub("Scrivi a a.b+c@example.test, non a aXb+c@example.test",
                List.of("a.b+c@example.test"))).isEqualTo("Scrivi a Membro anonimo, non a aXb+c@example.test");
        assertThat(PersonalData.scrub("Firma: Zed\\E(.*) [x]", List.of("Zed\\E(.*)")))
                .isEqualTo("Firma: Membro anonimo [x]");
        assertThat(PersonalData.scrub("Costo $1 per Ada", List.of("Ada"))).isEqualTo("Costo $1 per Membro anonimo");
    }

    @Test
    @DisplayName("Q-404: testo o valori vuoti o nulli non cambiano nulla")
    void scrubWithEmptyOrNullValues() {
        assertThat(PersonalData.scrub(null, List.of("Ada"))).isNull();
        assertThat(PersonalData.scrub("", List.of("Ada"))).isEmpty();
        assertThat(PersonalData.scrub("Ciao Ada", null)).isEqualTo("Ciao Ada");
        assertThat(PersonalData.scrub("Ciao Ada", List.of())).isEqualTo("Ciao Ada");
        assertThat(PersonalData.scrub("Ciao Ada", Arrays.asList(null, "", "   ", "Ada")))
                .isEqualTo("Ciao Membro anonimo");
        assertThat(PersonalData.tokens(Arrays.asList(null, "", "  "))).isEmpty();
        assertThat(PersonalData.redactAndScrub(null, List.of("Ada"))).isNull();
        assertThat(PersonalData.scrubAll(null, List.of("Ada"))).isNull();
    }

    @Test
    @DisplayName("Q-404: identificativi, envelope, stati e codici conservati; il testo libero si ripulisce")
    void redactAndScrubKeepsIdentifiersStatusesAndCodes() {
        JsonNode in = mapper.readTree("""
                {"id":"EVT-Ada","type":"io.loyaltyhub.fact.member.status.changed","subject":"member:MBR-000003",
                 "source":"urn:loyaltyhub:service:member","time":"2026-09-28T10:00:00Z",
                 "data":{"memberId":"MBR-000003","previousStatus":"ACTIVE","newStatus":"ANONYMIZED","status":"ACTIVE",
                         "rewardCode":"ADA","reason":"MEMBER_REQUEST","nickname":"Active",
                         "note":"Ada, Active da anni, ha chiesto la cancellazione"}}""");
        JsonNode out = PersonalData.redactAndScrub(in, List.of("Active", "Anon", "Ada"));
        JsonNode d = out.path("data");
        assertThat(out.path("id").asString()).isEqualTo("EVT-Ada");
        assertThat(out.path("type").asString()).isEqualTo("io.loyaltyhub.fact.member.status.changed");
        assertThat(d.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(d.path("previousStatus").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("newStatus").asString()).isEqualTo("ANONYMIZED");
        assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("rewardCode").asString()).isEqualTo("ADA");
        assertThat(d.path("reason").asString()).isEqualTo("MEMBER_REQUEST");
        assertThat(d.has("nickname")).isFalse();
        assertThat(d.path("note").asString())
                .isEqualTo("Membro anonimo, Membro anonimo da anni, ha chiesto la cancellazione");

        JsonNode kept = PersonalData.scrubAll(in, List.of("Active"));
        assertThat(kept.path("data").path("previousStatus").asString()).isEqualTo("ACTIVE");
        assertThat(kept.path("data").path("nickname").asString()).as("chiave personale, mai sicura")
                .isEqualTo("Membro anonimo");
    }

    @Test
    @DisplayName("Q-404: containsAny cerca parole intere")
    void containsAnyMatchesWholeWords() {
        assertThat(PersonalData.containsAny("Ciao ADA!", List.of("ada"))).isTrue();
        assertThat(PersonalData.containsAny("Adamo e ANONYMIZED", List.of("Ada", "Anon"))).isFalse();
        assertThat(PersonalData.containsAny(null, List.of("Ada"))).isFalse();
    }
}
