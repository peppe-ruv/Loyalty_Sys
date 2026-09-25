package io.loyaltyhub.campaign.engine;

import io.loyaltyhub.campaign.api.CreateCampaignRequest;
import io.loyaltyhub.campaign.application.CampaignAdminService;
import io.loyaltyhub.common.condition.ConditionRules;
import io.loyaltyhub.common.web.LhException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validazione di salvataggio delle condizioni di campagna (Q-215, Q-219, Q-222, Q-223, Q-224 decise): forma comune di
 * lh-common più il tipo dei campi calcolati dal motore ({@code member.*}, {@code context.*}, {@code history.*}).
 */
class ConditionValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode leaf(String field, String cmp, String value) {
        return JSON.readTree("{\"op\":\"all\",\"rules\":[{\"field\":\"" + field + "\",\"cmp\":\"" + cmp + "\",\"value\":"
                + value + "}]}");
    }

    @ParameterizedTest(name = "[{index}] {0} {1} {2} → {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            data.amount          | gte     | 50                  | OK
            data.amount          | gte     | "50"                | OK
            data.amount          | gte     | "abc"               | conditions.rules[0].value
            data.code            | eq      | 5                   | OK
            member.age           | gte     | "18"                | OK
            member.age           | gte     | " 18"               | conditions.rules[0].value
            member.tier          | eq      | 5                   | conditions.rules[0].value
            member.tier          | in      | ["GOLD",1]          | conditions.rules[0].value[1]
            member.segments      | contains| "SEG-VIP"           | OK
            context.hour         | between | [9,"18"]            | OK
            context.hour         | between | [9,"18:00"]         | conditions.rules[0].value[1]
            context.date         | gte     | "2026-09-01"        | OK
            context.date         | gte     | "2026-02-29"        | conditions.rules[0].value
            context.date         | gte     | "2026-09-01T00:00:00Z" | conditions.rules[0].value
            context.dayOfWeek    | in      | ["SAT","SUN"]       | OK
            history.actionCount  | eq      | true                | conditions.rules[0].value
            data.amount          | regex   | "1"                 | conditions.rules[0].cmp
            """)
    void leafValue(String field, String cmp, String value, String expected) {
        List<ConditionRules.Issue> issues = ConditionEvaluator.validate(leaf(field, cmp, value));
        if ("OK".equals(expected)) {
            assertThat(issues).isEmpty();
        } else {
            assertThat(issues).extracting(ConditionRules.Issue::path).first().isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            {"op":"all","rules":[]}                                      | OK
            {"op":"any","rules":[]}                                      | conditions.rules
            {"op":"xor","rules":[{"field":"data.a","cmp":"eq","value":1}]} | conditions.op
            {"op":"all","rules":[{"cmp":"eq","value":1}]}                | conditions.rules[0].field
            {"op":"all","rules":[{"field":"data.a","value":1}]}          | conditions.rules[0].cmp
            """)
    void shape(String conditions, String expected) {
        List<ConditionRules.Issue> issues = ConditionEvaluator.validate(JSON.readTree(conditions));
        if ("OK".equals(expected)) {
            assertThat(issues).isEmpty();
        } else {
            assertThat(issues).extracting(ConditionRules.Issue::path).first().isEqualTo(expected);
        }
    }

    @Test
    void saveRejectsWithConditionInvalid() {
        CampaignAdminService service = new CampaignAdminService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        CreateCampaignRequest r = new CreateCampaignRequest("CMP-TB-COND", "Condizioni", null, null, null,
                List.of("purchase.completed"), null, leaf("member.age", "gte", "\"diciotto\""),
                JSON.readTree("[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}]"), null,
                null, 100, null, false, List.of(), null);
        assertThatThrownBy(() -> service.create(r))
                .isInstanceOfSatisfying(LhException.class, e -> {
                    assertThat(e.code()).isEqualTo("CONDITION_INVALID");
                    assertThat(e.status().value()).isEqualTo(422);
                    assertThat(e.errors()).extracting(LhException.FieldError::field)
                            .containsExactly("conditions.rules[0].value");
                });
        assertThat(service.validate(r)).singleElement().asString().startsWith("conditions.rules[0].value:");
    }
}
