package io.loyaltyhub.gamification;

import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookGamAchievementTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("[TB-GAM-ACH-004] Metrica STREAK, DAY, buco")
    void testMetricStreakGaps() {
        Achievement streak = a("STREAK", null, "DAY", 3);
        Instant g1 = Instant.parse("2026-09-01T12:00:00Z");
        Instant g2 = Instant.parse("2026-09-02T12:00:00Z");
        Instant g4 = Instant.parse("2026-09-04T12:00:00Z"); // gap day 3

        var p1 = AchievementRules.advance(streak, AchievementRules.Progress.EMPTY, "action", data("{}"), g1);
        assertThat(p1.value()).isEqualTo(1);
        var p2 = AchievementRules.advance(streak, p1, "action", data("{}"), g2);
        assertThat(p2.value()).isEqualTo(2);

        var p4 = AchievementRules.advance(streak, p2, "action", data("{}"), g4);
        assertThat(p4.value()).as("Il buco azzera la serie, riparte da 1").isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-ACH-005] Filtro: action con field errato")
    void testFilterMismatch() throws Exception {
        JsonNode filter = JSON.readTree("{\"op\":\"all\",\"rules\":[{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":50}]}");
        Achievement filtered = aWithFilter("SUM", "data.amount", 100, filter);

        boolean match = AchievementRules.matches(filtered.filter(), data("{\"amount\":40}"));
        assertThat(match).isFalse();
    }

    @Test
    @DisplayName("[TB-GAM-ACH-006] Period MONTH, boundary")
    void testPeriodMonthBoundary() {
        Instant aug31 = Instant.parse("2026-08-31T23:30:00Z"); // 2026-09-01 01:30 in Rome
        assertThat(AchievementRules.periodKey("MONTH", aug31)).isEqualTo("2026-09");

        Instant aug31early = Instant.parse("2026-08-31T21:30:00Z"); // 2026-08-31 23:30 in Rome
        assertThat(AchievementRules.periodKey("MONTH", aug31early)).isEqualTo("2026-08");
    }

    // helpers
    private static Achievement a(String metric, String sumField, String streakUnit, long target) {
        return new Achievement("id", "code", "name", "desc", "icon", List.of("action"), null, metric, sumField, streakUnit, target, "EVER", true, null, "ACTIVE");
    }

    private static Achievement aWithFilter(String metric, String sumField, long target, JsonNode filter) {
        return new Achievement("id", "code", "name", "desc", "icon", List.of("action"), filter, metric, sumField, null, target, "EVER", true, null, "ACTIVE");
    }

    private static JsonNode data(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
