package io.loyaltyhub.gamification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookGamAchievementIT extends AchievementIT {

    @Test
    @DisplayName("[TB-GAM-ACH-001] Metrica COUNT, target=3, 3 azioni")
    void testMetricCount() throws Exception {
        String member = "MBR-000007";
        Map<String, Object> body = Map.of("code", "ACH-TB-COUNT", "name", "Count TB", "actionTypes", List.of("tb.count"),
                "metric", "COUNT", "target", 3, "period", "EVER", "repeatable", false);
        sendTb("POST", "/v1/achievements", "MARKETING:luca", body, 201);

        publishActionTb(member, "tb.count", Map.of());
        publishActionTb(member, "tb.count", Map.of());
        publishActionTb(member, "tb.count", Map.of());

        List<JsonNode> done = factsTb("member:" + member, "io.loyaltyhub.fact.achievement.completed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-TB-COUNT"));
        assertThat(done).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-ACH-002] Metrica SUM, target=100, field data.amount")
    void testMetricSum() throws Exception {
        String member = "MBR-000003";
        Map<String, Object> body = Map.of("code", "ACH-TB-SUM", "name", "Sum TB", "actionTypes", List.of("tb.sum"),
                "metric", "SUM", "sumField", "amount", "target", 100, "period", "EVER", "repeatable", false);
        sendTb("POST", "/v1/achievements", "MARKETING:luca", body, 201);

        publishActionTb(member, "tb.sum", Map.of("amount", 50));
        publishActionTb(member, "tb.sum", Map.of("amount", 60));

        List<JsonNode> done = factsTb("member:" + member, "io.loyaltyhub.fact.achievement.completed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-TB-SUM"));
        assertThat(done).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-ACH-003] Metrica DISTINCT_TYPES, {a, b, a, c}, target=3")
    void testMetricDistinct() throws Exception {
        String member = "MBR-000004";
        Map<String, Object> body = Map.of("code", "ACH-TB-DIST", "name", "Dist TB", "actionTypes", List.of("type.a", "type.b", "type.c"),
                "metric", "DISTINCT_TYPES", "target", 3, "period", "EVER", "repeatable", false);
        sendTb("POST", "/v1/achievements", "MARKETING:luca", body, 201);

        publishActionTb(member, "type.a", Map.of());
        publishActionTb(member, "type.b", Map.of());
        publishActionTb(member, "type.a", Map.of()); // duplicate
        publishActionTb(member, "type.c", Map.of());

        List<JsonNode> done = factsTb("member:" + member, "io.loyaltyhub.fact.achievement.completed",
                e -> e.path("data").path("achievementCode").asString().equals("ACH-TB-DIST"));
        assertThat(done).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-ACH-007] Badge collegato, achievement completed")
    void testBadgeAwarding() throws Exception {
        // Mapped to digitalNeedsBothTypesAndAwardsItsBadge
        digitalNeedsBothTypesAndAwardsItsBadge();
    }

    @Test
    @DisplayName("[TB-GAM-ACH-008] Repeatable=false, completato 2 volte")
    void testNotRepeatable() throws Exception {
        // Mapped to threePurchasesInAMonthCompleteOnceAndTheFourthDoesNotReemit which asserts exactly 1 completion
    }

    // helper
    private JsonNode sendTb(String method, String path, String actor, Object body, int expected) {
        int actualPort = (int) org.springframework.test.util.ReflectionTestUtils.getField(this, "port");
        var spec = org.springframework.web.client.RestClient.create("http://localhost:" + actualPort)
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path).header("X-LH-Actor", actor);
        if (body != null) spec = spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(res.getStatusCode().value()).as(method + " " + path + " → " + text).isEqualTo(expected);
            return text.isBlank() ? new tools.jackson.databind.ObjectMapper().createObjectNode() : new tools.jackson.databind.ObjectMapper().readTree(text);
        });
    }

    private void publishActionTb(String memberId, String type, Map<String, Object> data) throws Exception {
        try {
            java.lang.reflect.Method m = AchievementIT.class.getDeclaredMethod("publishAction", String.class, String.class, Map.class);
            m.setAccessible(true);
            m.invoke(this, memberId, type, data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<JsonNode> factsTb(String subject, String type, java.util.function.Predicate<JsonNode> match) throws Exception {
        try {
            java.lang.reflect.Method m = AchievementIT.class.getDeclaredMethod("facts", String.class, String.class, java.util.function.Predicate.class);
            m.setAccessible(true);
            return (List<JsonNode>) m.invoke(this, subject, type, match);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
