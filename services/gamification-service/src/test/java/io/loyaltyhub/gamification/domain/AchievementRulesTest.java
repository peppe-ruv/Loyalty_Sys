package io.loyaltyhub.gamification.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Metriche, periodi e filtri degli obiettivi (docs/03 §8). */
class AchievementRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void periodKeysFollowTheRomeCalendar() {
        // 31 agosto 23:30 UTC = 1 settembre a Roma.
        Instant t = Instant.parse("2026-08-31T23:30:00Z");
        assertThat(AchievementRules.periodKey("MONTH", t)).isEqualTo("2026-09");
        assertThat(AchievementRules.periodKey("EDITION", t)).isEqualTo("ED-2026");
        assertThat(AchievementRules.periodKey("EVER", t)).isEqualTo("EVER");
        assertThat(AchievementRules.unitKey("WEEK", Instant.parse("2026-09-24T10:00:00Z"))).isEqualTo("2026-W39");
    }

    @Test
    void countSumAndDistinctTypes() {
        var count = a("COUNT", null, null, 3);
        var p = AchievementRules.advance(count, AchievementRules.Progress.EMPTY, "purchase.completed", data("{}"), now());
        assertThat(p.value()).isEqualTo(1);

        var sum = a("SUM", "purchase.completed.data.amount", null, 1000);
        p = AchievementRules.advance(sum, new AchievementRules.Progress(900, List.of(), null), "purchase.completed",
                data("{\"amount\":130.75}"), now());
        assertThat(p.value()).isEqualTo(1030);

        var distinct = a("DISTINCT_TYPES", null, null, 2);
        p = AchievementRules.advance(distinct, AchievementRules.Progress.EMPTY, "ebill.activated", data("{}"), now());
        p = AchievementRules.advance(distinct, p, "ebill.activated", data("{}"), now());
        assertThat(p.value()).as("stesso tipo due volte conta una").isEqualTo(1);
        p = AchievementRules.advance(distinct, p, "directdebit.activated", data("{}"), now());
        assertThat(p.value()).isEqualTo(2);
        assertThat(p.distinctSeen()).containsExactly("ebill.activated", "directdebit.activated");
    }

    @Test
    void streakCountsConsecutiveDaysAndResetsOnAGap() {
        var streak = a("STREAK", null, "DAY", 7);
        var p = new AchievementRules.Progress(5, List.of(), "2026-09-23");
        var today = AchievementRules.advance(streak, p, "app.login.daily", data("{}"), Instant.parse("2026-09-24T08:00:00Z"));
        assertThat(today.value()).isEqualTo(6);
        assertThat(AchievementRules.advance(streak, today, "app.login.daily", data("{}"), Instant.parse("2026-09-24T20:00:00Z")))
                .as("stesso giorno: invariato").isEqualTo(today);
        assertThat(AchievementRules.advance(streak, today, "app.login.daily", data("{}"), Instant.parse("2026-09-26T08:00:00Z")).value())
                .as("un giorno saltato: si riparte").isEqualTo(1);
    }

    @Test
    void filterOnActionData() {
        JsonNode filter = data("{\"op\":\"all\",\"rules\":[{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":50},"
                + "{\"field\":\"data.channel\",\"cmp\":\"in\",\"value\":[\"ONLINE\",\"APP\"]}]}");
        assertThat(AchievementRules.matches(filter, data("{\"amount\":80,\"channel\":\"ONLINE\"}"))).isTrue();
        assertThat(AchievementRules.matches(filter, data("{\"amount\":30,\"channel\":\"ONLINE\"}"))).isFalse();
        assertThat(AchievementRules.matches(filter, data("{\"amount\":80,\"channel\":\"STORE\"}"))).isFalse();
        assertThat(AchievementRules.matches(null, data("{}"))).isTrue();
    }

    /** docs/03 §3.3: campo assente → la foglia è falsa, anche con {@code neq} (solo {@code nexists} è vera). */
    @Test
    void absentFieldMakesEveryLeafFalse() {
        JsonNode neq = data("{\"op\":\"all\",\"rules\":[{\"field\":\"data.channel\",\"cmp\":\"neq\",\"value\":\"STORE\"}]}");
        assertThat(AchievementRules.matches(neq, data("{\"amount\":80}"))).as("campo assente").isFalse();
        assertThat(AchievementRules.matches(neq, data("{\"channel\":null}"))).as("campo null").isFalse();
        assertThat(AchievementRules.matches(neq, data("{\"channel\":\"ONLINE\"}"))).isTrue();
        assertThat(AchievementRules.matches(neq, data("{\"channel\":\"STORE\"}"))).isFalse();
        for (String cmp : List.of("eq", "gt", "gte", "lt", "lte")) {
            JsonNode f = data("{\"rules\":[{\"field\":\"data.amount\",\"cmp\":\"" + cmp + "\",\"value\":10}]}");
            assertThat(AchievementRules.matches(f, data("{}"))).as(cmp + " su campo assente").isFalse();
        }
        JsonNode in = data("{\"rules\":[{\"field\":\"data.channel\",\"cmp\":\"in\",\"value\":[\"APP\"]}]}");
        assertThat(AchievementRules.matches(in, data("{}"))).isFalse();
        // Gruppo "any": la foglia sul campo assente non basta a far passare, un'altra foglia vera sì.
        JsonNode any = data("{\"op\":\"any\",\"rules\":[{\"field\":\"data.channel\",\"cmp\":\"neq\",\"value\":\"STORE\"},"
                + "{\"field\":\"data.amount\",\"cmp\":\"gte\",\"value\":50}]}");
        assertThat(AchievementRules.matches(any, data("{\"amount\":10}"))).isFalse();
        assertThat(AchievementRules.matches(any, data("{\"amount\":60}"))).isTrue();
    }

    private static Achievement a(String metric, String sumField, String unit, long target) {
        return new Achievement("id", "ACH-T", "T", null, null, List.of("x"), null, metric, sumField, unit, target, "EVER",
                false, null, "ACTIVE");
    }

    private static JsonNode data(String json) {
        return JSON.readTree(json);
    }

    private static Instant now() {
        return Instant.parse("2026-09-24T10:00:00Z");
    }
}
