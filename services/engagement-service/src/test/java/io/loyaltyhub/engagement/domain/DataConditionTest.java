package io.loyaltyhub.engagement.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Condizione delle regole di notifica sul solo spazio {@code data.*} (docs/03 §3.3, engagement-service.md §2). */
class DataConditionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    private final JsonNode referrer = json("""
            { "role": "REFERRER", "counterpartMemberId": "MBR-000009", "amount": 130.5,
              "items": [ { "category": "casa" }, { "category": "cucina" } ], "tags": ["a", "b"] }
            """);

    @Test
    void emptyConditionAlwaysMatches() {
        assertThat(DataCondition.matches(null, referrer)).isTrue();
        assertThat(DataCondition.matches(json("{}"), referrer)).isTrue();
    }

    @Test
    void leafComparators() {
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"REFERRER\"}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"REFEREE\"}"), referrer)).isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"value\":\"REFERRER\"}"), referrer))
                .as("cmp assente: foglia falsa, non eq di default (Q-219 DECISA)").isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":130}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.amount\",\"cmp\":\"between\",\"value\":[100,130]}"), referrer)).isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"in\",\"value\":[\"REFERRER\",\"X\"]}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.counterpartMemberId\",\"cmp\":\"startsWith\",\"value\":\"MBR-\"}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.tags\",\"cmp\":\"contains\",\"value\":\"b\"}"), referrer)).isTrue();
    }

    @Test
    void absentFieldsAndIncompatibleTypesAreFalseNeverErrors() {
        assertThat(DataCondition.matches(json("{\"field\":\"data.origin\",\"cmp\":\"eq\",\"value\":\"CAMPAIGN\"}"), referrer)).isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"data.origin\",\"cmp\":\"nexists\"}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"exists\"}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"gt\",\"value\":3}"), referrer)).isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"GOLD\"}"), referrer))
                .as("fuori da data.* il campo è assente").isFalse();
        assertThat(DataCondition.matches(json("{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"REFERRER\"}"), null)).isFalse();
    }

    @Test
    void arraysMatchWhenAnyElementMatches() {
        assertThat(DataCondition.matches(json("{\"field\":\"data.items[*].category\",\"cmp\":\"eq\",\"value\":\"cucina\"}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.items[*].category\",\"cmp\":\"in\",\"value\":[\"cucina\"]}"), referrer)).isTrue();
        assertThat(DataCondition.matches(json("{\"field\":\"data.items[*].category\",\"cmp\":\"eq\",\"value\":\"bagno\"}"), referrer)).isFalse();
    }

    @Test
    void groups() {
        JsonNode all = json("""
                { "op": "all", "rules": [ { "field": "data.role", "cmp": "eq", "value": "REFERRER" },
                                          { "field": "data.amount", "cmp": "lt", "value": 100 } ] }""");
        JsonNode any = json("""
                { "op": "any", "rules": [ { "field": "data.role", "cmp": "eq", "value": "REFEREE" },
                                          { "field": "data.amount", "cmp": "gt", "value": 100 } ] }""");
        JsonNode not = json("""
                { "op": "not", "rules": [ { "field": "data.role", "cmp": "eq", "value": "REFEREE" } ] }""");
        assertThat(DataCondition.matches(all, referrer)).isFalse();
        assertThat(DataCondition.matches(any, referrer)).isTrue();
        assertThat(DataCondition.matches(not, referrer)).isTrue();
    }

    @Test
    void validationForManagement() {
        assertThat(DataCondition.problems(null)).isEmpty();
        assertThat(DataCondition.problems(json("{\"op\":\"all\",\"rules\":[{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"X\"}]}"))).isEmpty();
        assertThat(DataCondition.problems(json("{\"field\":\"member.tier\",\"cmp\":\"eq\",\"value\":\"GOLD\"}")))
                .singleElement().asString().contains("solo data.*");
        assertThat(DataCondition.problems(json("{\"field\":\"data.role\",\"cmp\":\"like\",\"value\":\"X\"}")))
                .singleElement().asString().contains("comparatore");
        assertThat(DataCondition.problems(json("{\"field\":\"data.role\",\"cmp\":\"eq\"}")))
                .singleElement().asString().contains("richiede value");
        assertThat(DataCondition.problems(json("{\"op\":\"xor\",\"rules\":[]}"))).singleElement().asString().contains("xor");
    }

    /** Q-179 DECISA: any vuoto, cmp assente e foglia senza campo rifiutati al salvataggio (e falsi nel motore). */
    @Test
    void conservativeValidation() {
        assertThat(DataCondition.problems(json("{\"op\":\"any\",\"rules\":[]}"))).singleElement().asString()
                .startsWith("condition.rules:");
        assertThat(DataCondition.problems(json("{\"field\":\"data.role\",\"value\":\"X\"}"))).singleElement().asString()
                .contains("comparatore mancante");
        assertThat(DataCondition.problems(json("{\"op\":\"all\",\"rules\":[{\"cmp\":\"eq\",\"value\":1}]}")))
                .singleElement().asString().startsWith("condition.rules[0].field:");
        assertThat(DataCondition.matches(json("{\"op\":\"any\",\"rules\":[]}"), json("{}"))).isFalse();
        assertThat(DataCondition.matches(json("{\"op\":\"xor\",\"rules\":[]}"), json("{}"))).isFalse();
    }
}
