package io.loyaltyhub.common.privacy;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regole condivise dell'anonimizzazione (F-MBR-05, M7.5). */
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
}
