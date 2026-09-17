package it.iren.loyalty.segmentservice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentEvaluatorTest {
    private final SegmentEvaluator ev = new SegmentEvaluator();
    private final Instant now = Instant.parse("2027-03-10T10:00:00Z");

    private MemberSnapshot member() {
        return new MemberSnapshot("m1", Instant.parse("2026-03-12T09:00:00Z"), "PLUS", Map.of("segmento", "domestico"), Map.of("newsletter", true), 800, List.of(
                new MemberSnapshot.ActionSummary("TRANSACTION", now.minus(Duration.ofDays(5)), new BigDecimal("120"), "negozio",
                        List.of(new MemberSnapshot.Line("MANUT-CALDAIA-STD", "IrenPlus", "servizi", List.of("green")))),
                new MemberSnapshot.ActionSummary("TRANSACTION", now.minus(Duration.ofDays(200)), new BigDecimal("80"), "web", List.of()),
                new MemberSnapshot.ActionSummary("SELF_READING_SENT", now.minus(Duration.ofDays(1)), null, "app", List.of())));
    }

    private Segment seg(Segment.Type t, Map<String, Object> p) { return new Segment("s", "s", true, Segment.Match.ALL, List.of(new Segment.Criterion(t, p))); }

    @Test void countValueAndRecencyWithWindows() {
        assertThat(ev.matches(seg(Segment.Type.ACTION_COUNT, Map.of("actionType", "TRANSACTION", "min", 2)), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.ACTION_COUNT, Map.of("actionType", "TRANSACTION", "min", 2, "days", 30)), member(), now)).isFalse();
        assertThat(ev.matches(seg(Segment.Type.ACTION_VALUE, Map.of("min", 150, "days", 365)), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.AVG_ACTION_VALUE, Map.of("min", 100, "max", 100)), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.LAST_ACTION_DAYS_AGO, Map.of("min", 90)), member(), now)).isFalse();
        assertThat(ev.matches(seg(Segment.Type.LAST_ACTION_DAYS_AGO, Map.of("actionType", "TRANSACTION", "max", 7)), member(), now)).isTrue();
    }

    @Test void linesChannelsLabelsAndProfile() {
        assertThat(ev.matches(seg(Segment.Type.BOUGHT_LABEL, Map.of("values", List.of("green"))), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.BOUGHT_BRAND, Map.of("values", "IrenPlus,Altro")), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.BOUGHT_SKU, Map.of("values", List.of("X"))), member(), now)).isFalse();
        assertThat(ev.matches(seg(Segment.Type.CHANNEL_SHARE, Map.of("channel", "negozio", "minPercent", 60)), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.ACTION_IN_CHANNEL, Map.of("values", List.of("call-center"))), member(), now)).isFalse();
        assertThat(ev.matches(seg(Segment.Type.LABEL_VALUE, Map.of("key", "segmento", "value", "domestico")), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.HAS_LABEL, Map.of("values", List.of("vip"))), member(), now)).isFalse();
        assertThat(ev.matches(seg(Segment.Type.TIER, Map.of("values", List.of("PLUS", "TOP"))), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.POINTS_BALANCE, Map.of("min", 500, "max", 1000)), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.CONSENT, Map.of("key", "newsletter")), member(), now)).isTrue();
        assertThat(ev.matches(seg(Segment.Type.STATIC_LIST, Map.of("values", List.of("m1", "m2"))), member(), now)).isTrue();
    }

    @Test void anniversaryWithinWindowAndAnyMatch() {
        assertThat(ev.matches(seg(Segment.Type.ANNIVERSARY, Map.of("days", 7)), member(), now)).isTrue();   // 12 marzo, tra 2 giorni
        assertThat(ev.matches(seg(Segment.Type.ANNIVERSARY, Map.of("days", 1)), member(), now)).isFalse();
        Segment any = new Segment("a", "a", true, Segment.Match.ANY, List.of(
                new Segment.Criterion(Segment.Type.TIER, Map.of("values", List.of("TOP"))),
                new Segment.Criterion(Segment.Type.CONSENT, Map.of("key", "newsletter"))));
        assertThat(ev.matches(any, member(), now)).isTrue();
    }
}
