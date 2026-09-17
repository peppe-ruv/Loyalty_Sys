package it.iren.loyalty.tierservice.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TierPolicyTest {
    private final TierPolicy p = TierPolicy.example();

    @Test void upgradeIsImmediateAndNeverDownDuringYear() {
        var base = p.byCode("BASE");
        assertThat(p.duringYear(base, 1_500).code()).isEqualTo("PLUS");
        assertThat(p.duringYear(p.byCode("TOP"), 100).code()).isEqualTo("TOP");
    }
    @Test void yearEndDropsAtMostOneLevel() {
        assertThat(p.atYearEnd(p.byCode("TOP"), 0).code()).isEqualTo("PLUS");
        assertThat(p.atYearEnd(p.byCode("TOP"), 4_000).code()).isEqualTo("TOP");
        assertThat(p.atYearEnd(p.byCode("PLUS"), 5_000).code()).isEqualTo("TOP");
        assertThat(p.atYearEnd(p.byCode("BASE"), 0).code()).isEqualTo("BASE");
    }
}
