package it.iren.loyalty.fraudservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {
    final RiskEngine engine = new RiskEngine();
    final RiskPolicy policy = RiskPolicy.example();
    final Instant now = Instant.parse("2027-03-10T11:00:00Z");

    @Test void quietMemberIsLow() {
        var a = engine.assess(RiskEngine.MemberActivity.quiet("m1"), policy, now);
        assertThat(a.score()).isZero();
        assertThat(a.level()).isEqualTo("LOW");
        assertThat(a.reasonCodes()).isEmpty();
        assertThat(a.blockedBy(policy)).isFalse();
    }

    @Test void signalsAddUpWithReasonsOrderedByContribution() {
        var a = engine.assess(new RiskEngine.MemberActivity("m2", 10, 5, 0, 0, null, 0, null, 0, 0, 0, 1, 0), policy, now);
        assertThat(a.score()).isEqualTo(70); // REDEMPTION_FREQUENCY 30 + MULTI_ACCOUNT_DEVICE 40, entrambi saturi
        assertThat(a.level()).isEqualTo("HIGH");
        assertThat(a.reasonCodes()).containsExactly("MULTI_ACCOUNT_DEVICE", "REDEMPTION_FREQUENCY");
        assertThat(a.blockedBy(policy)).isFalse();
    }

    @Test void criticalBlocksAndDisabledSignalsAreIgnored() {
        var a = engine.assess(new RiskEngine.MemberActivity("m3", 10, 5, 0, 0, null, 0, 1000.0, 0, 0, 0, 1, 0), policy, now);
        assertThat(a.level()).isEqualTo("CRITICAL");
        assertThat(a.score()).isEqualTo(100);
        assertThat(a.blockedBy(policy)).isTrue();
        var noTravel = new java.util.EnumMap<>(policy.signals());
        noTravel.put(RiskPolicy.Signal.IMPOSSIBLE_TRAVEL, new RiskPolicy.SignalSpec(false, 45, 150, 900, ""));
        var relaxed = new RiskPolicy("r", "2", noTravel, 30, 60, 85, "CRITICAL", 72, 5);
        assertThat(engine.assess(new RiskEngine.MemberActivity("m3", 10, 5, 0, 0, null, 0, 1000.0, 0, 0, 0, 1, 0), relaxed, now).level()).isEqualTo("HIGH");
    }

    @Test void intensityIsLinearAndRefundRatioNeedsMinimumTransactions() {
        var spec = policy.signals().get(RiskPolicy.Signal.REDEMPTION_FREQUENCY); // soglia 3, saturazione 10
        assertThat(spec.intensity(3)).isZero();
        assertThat(spec.intensity(6.5)).isEqualTo(0.5);
        assertThat(spec.intensity(20)).isEqualTo(1.0);
        var few = engine.assess(new RiskEngine.MemberActivity("m4", 0, 1, 0, 0, null, 0, null, 0, 3, 3, 1, 0), policy, now);
        assertThat(few.reasonCodes()).doesNotContain("REFUND_RATIO");
        var many = engine.assess(new RiskEngine.MemberActivity("m4", 0, 1, 0, 0, null, 0, null, 0, 10, 9, 1, 0), policy, now);
        assertThat(many.reasonCodes()).contains("REFUND_RATIO");
    }

    @Test void geoHelpersAndDecay() {
        double km = RiskEngine.distanceKm(45.0703, 7.6869, 41.9028, 12.4964); // Torino → Roma
        assertThat(km).isBetween(520.0, 530.0);
        assertThat(RiskEngine.speedKmh(45.0703, 7.6869, now, 41.9028, 12.4964, now.plusSeconds(1800))).isGreaterThan(1000);
        assertThat(RiskEngine.decayed(80, now, now.plusSeconds(72 * 3600), policy)).isEqualTo(40);
    }
}
