package it.iren.loyalty.memberservice.domain;

import it.iren.loyalty.common.event.EventTypes;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralPolicyTest {
    private final ReferralPolicy p = new ReferralPolicy(ReferralPolicy.Trigger.ON_FIRST_ACTION, 30, 10, "secret");

    @Test void codeIsStableReadableAndDistinct() {
        assertThat(p.codeFor("m1")).hasSize(8).matches("[A-Z2-9]{8}").doesNotContain("0", "O", "1", "I").isEqualTo(p.codeFor("m1"));
        assertThat(p.codeFor("m1")).isNotEqualTo(p.codeFor("m2"));
        assertThat(new ReferralPolicy(ReferralPolicy.Trigger.ON_FIRST_ACTION, 30, 10, "other").codeFor("m1")).isNotEqualTo(p.codeFor("m1"));
    }

    @Test void completionTriggerAndTwoIdempotentActions() {
        assertThat(p.completes(EventTypes.ACTION_MEMBER_ENROLLED)).isFalse();
        assertThat(p.completes("BILL_PAID_ON_TIME")).isTrue();
        var actions = p.completed("referrer", "referred", Instant.EPOCH);
        assertThat(actions).extracting(a -> a.actionType()).containsExactly(EventTypes.ACTION_REFERRAL_COMPLETED, EventTypes.ACTION_REFERRED_ENROLLED);
        assertThat(actions).extracting(a -> a.idempotencyKey()).containsExactly("referral:referrer:referred:REFERRER", "referral:referrer:referred:REFERRED");
    }
}
