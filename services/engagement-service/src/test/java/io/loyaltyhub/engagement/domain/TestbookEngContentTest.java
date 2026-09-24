package io.loyaltyhub.engagement.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookEngContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    @ParameterizedTest(name = "[{0}] {1}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-content-selection.csv", numLinesToSkip = 1)
    void testbookContentSelection(
            String id, String status, Integer startOffsetHours, Integer endOffsetHours,
            String itemTiers, String itemSegments, String itemStatuses, Integer itemRegisteredDays, String itemDays,
            String viewerTier, String viewerSegments, String viewerStatus, Integer viewerRegisteredDaysAge, String viewerTodayDay,
            boolean expected) {

        ObjectNode audience = MAPPER.createObjectNode();
        if (itemTiers != null) audience.set("tiers", toArray(itemTiers));
        if (itemSegments != null) audience.set("segments", toArray(itemSegments));
        if (itemStatuses != null) audience.set("statuses", toArray(itemStatuses));
        if (itemRegisteredDays != null) audience.put("registeredWithinDays", itemRegisteredDays);
        if (itemDays != null) audience.set("daysOfWeek", toArray(itemDays));

        Instant startAt = startOffsetHours != null ? NOW.plus(startOffsetHours, ChronoUnit.HOURS) : null;
        Instant endAt = endOffsetHours != null ? NOW.plus(endOffsetHours, ChronoUnit.HOURS) : null;

        ContentItem item = new ContentItem("id", "CODE", "BANNER", "HOME_GRID", "Title", "Body", "url",
                "cta", "url", "NONE", null, audience, startAt, endAt, 10, null, false, null, status, 1);

        Instant registeredAt = viewerRegisteredDaysAge != null ? NOW.minus(viewerRegisteredDaysAge, ChronoUnit.DAYS) : null;
        List<String> segs = viewerSegments != null ? List.of(viewerSegments.split(";")) : List.of();

        ContentSelection.Viewer viewer = new ContentSelection.Viewer(viewerTier, segs, viewerStatus, registeredAt);

        // Per testare daysOfWeek forziamo la data attuale tramite un now specifico che corrisponde a viewerTodayDay se fornito
        Instant effectiveNow = NOW;
        if (viewerTodayDay != null) {
            // Find a date that matches the day of week.
            LocalDate date = LocalDate.of(2026, 10, 26); // Monday
            while (!date.getDayOfWeek().name().substring(0, 3).equals(viewerTodayDay)) {
                date = date.plusDays(1);
            }
            effectiveNow = date.atStartOfDay(ZoneId.of("Europe/Rome")).toInstant().plus(12, ChronoUnit.HOURS);
            // Re-adjust registeredAt relative to effectiveNow
            if (viewerRegisteredDaysAge != null) {
                viewer = new ContentSelection.Viewer(viewerTier, segs, viewerStatus, effectiveNow.minus(viewerRegisteredDaysAge, ChronoUnit.DAYS));
            }
        }

        String reason = ContentSelection.exclusion(item, viewer, effectiveNow);

        if (expected) {
            assertThat(reason).as("Expected visible").isNull();
        } else {
            assertThat(reason).as("Expected excluded").isNotNull();
        }
    }

    @ParameterizedTest(name = "[{0}] freq: {1}, lastSeen: {2}")
    @CsvFileSource(resources = "/testbook/engagement/TB-ENG-popup-frequency.csv", numLinesToSkip = 1)
    void testbookPopupFrequency(String id, String frequency, Integer lastSeenDaysAgo, boolean expected) {
        LocalDate today = LocalDate.ofInstant(NOW, ContentSelection.ZONE);
        LocalDate lastSeen = lastSeenDaysAgo != null ? today.minusDays(lastSeenDaysAgo) : null;

        boolean allowed = ContentSelection.frequencyAllows(frequency, lastSeen, today);
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-ENG-CNT-019] Priority ties break")
    void priorityTiesBreak() {
        ContentItem a = buildItem("A", "Z", 10);
        ContentItem b = buildItem("B", "X", 20);
        ContentItem c = buildItem("C", "A", 10);

        ContentSelection.Result res = ContentSelection.select(List.of(a, b, c), ContentSelection.Viewer.UNKNOWN, NOW, 2);

        assertThat(res.shown()).hasSize(2);
        assertThat(res.shown().get(0).id()).isEqualTo("B"); // Highest priority
        assertThat(res.shown().get(1).id()).isEqualTo("C"); // Tie break on code A < Z
    }

    @Test
    @DisplayName("[TB-ENG-CNT-020] Limite CATALOG_TOP = 1")
    void limitCatalogTop() {
        ContentItem a = buildItem("A", "C1", 10);
        ContentItem b = buildItem("B", "C2", 10);

        ContentSelection.Result res = ContentSelection.select(List.of(a, b), ContentSelection.Viewer.UNKNOWN, NOW, ContentSelection.LIMITS.get("CATALOG_TOP"));

        assertThat(res.shown()).hasSize(1);
        assertThat(res.shown().get(0).id()).isEqualTo("A");
    }

    private ArrayNode toArray(String semicolonSeparated) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (String s : semicolonSeparated.split(";")) {
            arr.add(s);
        }
        return arr;
    }

    private ContentItem buildItem(String id, String code, int priority) {
        return new ContentItem(id, code, "BANNER", "HOME_GRID", "Title", "Body", "url",
                "cta", "url", "NONE", null, MAPPER.createObjectNode(), null, null, priority, null, false, null, "LIVE", 1);
    }
}
