package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.domain.ContentSelection.Viewer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Selezione per posizionamento (docs/03 §9, F-CNT-01/04): stato, calendario, pubblico, priorità, limite, motivi. */
class ContentSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Viewer ANNA = new Viewer("BASE", List.of(), "ACTIVE", NOW.minus(Duration.ofDays(1)));
    private static final Viewer DAVIDE = new Viewer("GOLD", List.of("SEG-DIGITAL"), "ACTIVE", NOW.minus(Duration.ofDays(1500)));

    @Test
    void ordersByPriorityThenCodeAndCutsAtTheLimit() {
        List<ContentItem> items = List.of(item("B", 50, "{}"), item("A", 50, "{}"), item("TOP", 90, "{}"));
        ContentSelection.Result r = ContentSelection.select(items, ANNA, NOW, 2);
        assertThat(r.shown()).extracting(ContentItem::code).containsExactly("TOP", "A");
        assertThat(r.excluded()).isEmpty();
    }

    @Test
    void explainsEveryExclusion() {
        List<ContentItem> items = List.of(
                with(item("DRAFT", 99, "{}"), "DRAFT", null, null),
                with(item("FUTURE", 99, "{}"), "LIVE", NOW.plusSeconds(3600), null),
                with(item("OVER", 99, "{}"), "LIVE", null, NOW),
                item("GOLD", 99, "{\"tiers\":[\"GOLD\",\"PLATINUM\"]}"),
                item("DIGITAL", 99, "{\"segments\":[\"SEG-DIGITAL\"]}"),
                item("ALL", 10, "{\"tiers\":[],\"segments\":[],\"statuses\":[]}"));
        ContentSelection.Result anna = ContentSelection.select(items, ANNA, NOW, 6);
        assertThat(anna.shown()).extracting(ContentItem::code).containsExactly("ALL");
        assertThat(anna.excluded()).extracting(e -> e.item().code() + ":" + e.reason()).containsExactlyInAnyOrder(
                "DRAFT:NOT_LIVE", "FUTURE:OUT_OF_SCHEDULE", "OVER:OUT_OF_SCHEDULE", "GOLD:NOT_IN_AUDIENCE",
                "DIGITAL:NOT_IN_AUDIENCE");
        assertThat(ContentSelection.select(items, DAVIDE, NOW, 6).shown()).extracting(ContentItem::code)
                .containsExactly("DIGITAL", "GOLD", "ALL");
    }

    @Test
    void unknownMemberSeesOnlyContentForEveryone() {
        assertThat(ContentSelection.inAudience(json("{\"tiers\":[\"GOLD\"]}"), Viewer.UNKNOWN, NOW)).isFalse();
        assertThat(ContentSelection.inAudience(json("{}"), Viewer.UNKNOWN, NOW)).isTrue();
        assertThat(ContentSelection.inAudience(json("{\"statuses\":[\"ACTIVE\"]}"), ANNA, NOW)).isTrue();
        assertThat(ContentSelection.inAudience(json("{\"statuses\":[\"BLOCKED\"]}"), ANNA, NOW)).isFalse();
    }

    /** docs/servizi/engagement-service.md §7: ONCE visto → non ricompare; ONCE_PER_DAY ricompare il giorno dopo. */
    @Test
    void popupFrequencies() {
        LocalDate today = LocalDate.ofInstant(NOW, ContentSelection.ZONE);
        assertThat(ContentSelection.frequencyAllows("ONCE", null, today)).isTrue();
        assertThat(ContentSelection.frequencyAllows("ONCE", today.minusDays(30), today)).isFalse();
        assertThat(ContentSelection.frequencyAllows("ONCE_PER_DAY", today, today)).isFalse();
        assertThat(ContentSelection.frequencyAllows("ONCE_PER_DAY", today.minusDays(1), today)).isTrue();
        assertThat(ContentSelection.frequencyAllows("ALWAYS", today, today)).isTrue();

        ContentItem once = popup("POP-ONCE", 90, "ONCE", "{}");
        ContentItem daily = popup("POP-DAILY", 80, "ONCE_PER_DAY", "{}");
        ContentSelection.Result first = ContentSelection.selectPopup(List.of(once, daily), ANNA, NOW, Map.of());
        assertThat(first.shown()).extracting(ContentItem::code).containsExactly("POP-ONCE");
        ContentSelection.Result after = ContentSelection.selectPopup(List.of(once, daily), ANNA, NOW,
                Map.of("POP-ONCE", today.minusDays(3), "POP-DAILY", today));
        assertThat(after.shown()).isEmpty();
        assertThat(after.excluded()).extracting(e -> e.item().code() + ":" + e.reason())
                .containsExactlyInAnyOrder("POP-ONCE:FREQUENCY", "POP-DAILY:FREQUENCY");
        ContentSelection.Result nextDay = ContentSelection.selectPopup(List.of(once, daily), ANNA, NOW.plus(Duration.ofDays(1)),
                Map.of("POP-ONCE", today.minusDays(3), "POP-DAILY", today));
        assertThat(nextDay.shown()).extracting(ContentItem::code).containsExactly("POP-DAILY");
    }

    @Test
    void popupAudienceByRegistrationAndWeekday() {
        JsonNode recent = json("{\"registeredWithinDays\":7}");
        assertThat(ContentSelection.inAudience(recent, ANNA, NOW)).as("iscritta ieri").isTrue();
        assertThat(ContentSelection.inAudience(recent, DAVIDE, NOW)).isFalse();
        assertThat(ContentSelection.inAudience(recent, Viewer.UNKNOWN, NOW)).as("iscrizione ignota").isFalse();
        // 2026-09-24 è giovedì; 2026-09-26 sabato.
        JsonNode weekend = json("{\"daysOfWeek\":[\"SAT\",\"SUN\"]}");
        assertThat(ContentSelection.inAudience(weekend, ANNA, NOW)).isFalse();
        assertThat(ContentSelection.inAudience(weekend, ANNA, Instant.parse("2026-09-26T09:00:00Z"))).isTrue();
    }

    private static ContentItem popup(String code, int priority, String frequency, String audience) {
        return new ContentItem(code, code, "POPUP", null, code, null, null, null, null, "NONE", null, json(audience),
                null, null, priority, frequency, true, json("{}"), "LIVE", 0, null);
    }

    private static ContentItem item(String code, int priority, String audience) {
        return new ContentItem(code, code, "CARD", "HOME_GRID", code, null, null, null, null, "NONE", null, json(audience),
                null, null, priority, null, true, json("{}"), "LIVE", 0, null);
    }

    private static ContentItem with(ContentItem c, String status, Instant start, Instant end) {
        return new ContentItem(c.id(), c.code(), c.kind(), c.placement(), c.title(), c.body(), c.imageUrl(), c.ctaLabel(),
                c.ctaTarget(), c.linkType(), c.linkCode(), c.audience(), start, end, c.priority(), c.frequency(),
                c.dismissible(), c.style(), status, c.version(), c.updatedAt());
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }
}
