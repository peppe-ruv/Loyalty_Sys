package it.iren.loyalty.catalogredemption.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedemptionPolicyTest {
    private final Instant now = Instant.parse("2027-05-01T10:00:00Z");

    private RewardDefinition reward(long cost, int minTier, long stock, int perMember, Set<String> segments) {
        return new RewardDefinition("r", "Buono 10€", RewardDefinition.Type.VOUCHER, new BigDecimal("10"), cost, minTier, stock, perMember, 0,
                null, null, Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-12-31T00:00:00Z"), segments, "buoni", "pool-1", 90, true);
    }

    @Test void checksInPriorityOrder() {
        var m = new RedemptionPolicy.MemberState(1, Set.of("green-customers"), 500, 0, 0);
        assertThat(RedemptionPolicy.check(reward(300, 0, 5, 0, Set.of()), m, now)).isEqualTo(RedemptionPolicy.Reason.OK);
        assertThat(RedemptionPolicy.check(reward(300, 2, 5, 0, Set.of()), m, now)).isEqualTo(RedemptionPolicy.Reason.TIER_TOO_LOW);
        assertThat(RedemptionPolicy.check(reward(300, 0, 5, 0, Set.of("vip")), m, now)).isEqualTo(RedemptionPolicy.Reason.SEGMENT_NOT_TARGETED);
        assertThat(RedemptionPolicy.check(reward(300, 0, 0, 0, Set.of()), m, now)).isEqualTo(RedemptionPolicy.Reason.OUT_OF_STOCK);
        assertThat(RedemptionPolicy.check(reward(300, 0, -1, 0, Set.of()), m, now)).isEqualTo(RedemptionPolicy.Reason.OK);
        assertThat(RedemptionPolicy.check(reward(300, 0, 5, 1, Set.of()), new RedemptionPolicy.MemberState(1, Set.of(), 500, 1, 0), now)).isEqualTo(RedemptionPolicy.Reason.MEMBER_LIMIT);
        assertThat(RedemptionPolicy.check(reward(900, 0, 5, 0, Set.of()), m, now)).isEqualTo(RedemptionPolicy.Reason.INSUFFICIENT_BALANCE);
        assertThat(RedemptionPolicy.check(reward(300, 0, 5, 0, Set.of()), m, Instant.parse("2028-01-01T00:00:00Z"))).isEqualTo(RedemptionPolicy.Reason.ENDED);
    }

    @Test void lifecycle() {
        assertThat(RedemptionState.CONFIRMED.canGo(RedemptionState.USED)).isTrue();
        assertThat(RedemptionState.IN_DELIVERY.cancellableByMember()).isFalse();
        assertThat(RedemptionState.USED.canGo(RedemptionState.CANCELLED)).isFalse();
        assertThat(RedemptionState.DELIVERED.canGo(RedemptionState.DONATED)).isTrue();
    }
}
