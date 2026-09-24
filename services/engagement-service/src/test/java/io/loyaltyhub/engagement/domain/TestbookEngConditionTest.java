package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookEngConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "[{0}] {1} on {2}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-condition.csv", numLinesToSkip = 1)
    void testbookConditionEval(String id, String cmp, String field, String valueJson, String actualJson, boolean expected) throws Exception {
        JsonNode value = valueJson == null || valueJson.isEmpty() ? null : MAPPER.readTree(valueJson);
        JsonNode actual = MAPPER.readTree(actualJson);

        var conditionNode = MAPPER.createObjectNode();
        conditionNode.put("cmp", cmp);
        conditionNode.put("field", field);
        if (value != null) {
            conditionNode.set("value", value);
        }

        boolean match = DataCondition.matches(conditionNode, actual);
        assertThat(match).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-ENG-CND-007] Group all")
    void groupAll() throws Exception {
        String json = """
        {
          "op": "all",
          "rules": [
            { "field": "data.x", "cmp": "eq", "value": 1 },
            { "field": "data.x", "cmp": "eq", "value": 2 }
          ]
        }
        """;
        JsonNode condition = MAPPER.readTree(json);
        JsonNode actual = MAPPER.readTree("{\"x\": 1}");

        boolean match = DataCondition.matches(condition, actual);
        assertThat(match).isFalse();
    }
}
