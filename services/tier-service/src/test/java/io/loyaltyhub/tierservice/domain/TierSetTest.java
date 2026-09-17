package io.loyaltyhub.tierservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TierSetTest {
    @Test void defaultSetSoftDowngradeAndProgress() {
        var ts = TierSet.example();
        var m = new TierSet.MemberMetrics(Map.of("status", 1600d), Instant.parse("2026-01-01T00:00:00Z"), null);
        assertThat(ts.qualified(m).code()).isEqualTo("PLUS");
        assertThat(ts.duringPeriod(ts.byCode("TOP"), m).code()).isEqualTo("TOP");
        assertThat(ts.atReview(ts.byCode("TOP"), new TierSet.MemberMetrics(Map.of("status", 0d), null, null)).code()).isEqualTo("PLUS");
        assertThat(ts.missingToNext(ts.byCode("PLUS"), m)).containsEntry("status", 2400d);
    }

    @Test void multiConditionAnyAndDowngradeModes() {
        var ts = new TierSet("m", "m", true, List.of(new TierSet.Condition("units", TierSet.Metric.TOTAL_EARNED_UNITS, "PREMIO", 0), new TierSet.Condition("months", TierSet.Metric.MONTHS_SINCE_JOINING, null, 0)), TierSet.Match.ANY,
                List.of(new TierSet.Tier("B", 0, Map.of(), null), new TierSet.Tier("G", 1, Map.of("units", 5000d, "months", 24d), null)), new TierSet.Downgrade(TierSet.Downgrade.Mode.INTERVAL_MONTHS, List.of(), 6));
        Instant t = Instant.parse("2027-03-10T10:00:00Z");
        assertThat(ts.qualified(new TierSet.MemberMetrics(Map.of("units", 100d, "months", 30d), null, null)).code()).isEqualTo("G");
        assertThat(ts.nextReview(new TierSet.MemberMetrics(Map.of(), t, t), LocalDate.of(2027, 3, 10))).isEqualTo(LocalDate.of(2027, 9, 10));
        var auto = new TierSet("a", "a", true, List.of(new TierSet.Condition("s", TierSet.Metric.ACTIVE_UNITS, "PREMIO", 0)), TierSet.Match.ALL, List.of(new TierSet.Tier("B", 0, Map.of(), null), new TierSet.Tier("S", 1, Map.of("s", 100d), null)), new TierSet.Downgrade(TierSet.Downgrade.Mode.AUTOMATIC, List.of(), 0));
        assertThat(auto.duringPeriod(auto.byCode("S"), new TierSet.MemberMetrics(Map.of("s", 50d), null, null)).code()).isEqualTo("B");
    }
}
