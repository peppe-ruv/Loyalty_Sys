package io.loyaltyhub.engagement.domain;

import io.loyaltyhub.engagement.domain.ContentSelection.Viewer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Selezione per posizionamento (docs/03 §9, F-CNT-01/04): stato, calendario, pubblico, priorità, limite, motivi. */
class ContentSelectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Viewer ANNA = new Viewer("BASE", List.of(), "ACTIVE");
    private static final Viewer DAVIDE = new Viewer("GOLD", List.of("SEG-DIGITAL"), "ACTIVE");

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
        assertThat(ContentSelection.inAudience(json("{\"tiers\":[\"GOLD\"]}"), Viewer.UNKNOWN)).isFalse();
        assertThat(ContentSelection.inAudience(json("{}"), Viewer.UNKNOWN)).isTrue();
        assertThat(ContentSelection.inAudience(json("{\"statuses\":[\"ACTIVE\"]}"), ANNA)).isTrue();
        assertThat(ContentSelection.inAudience(json("{\"statuses\":[\"BLOCKED\"]}"), ANNA)).isFalse();
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
