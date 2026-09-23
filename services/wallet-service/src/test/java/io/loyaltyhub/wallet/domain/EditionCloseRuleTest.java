package io.loyaltyhub.wallet.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EditionCloseRuleTest {

    private final List<Tier> scale = List.of(
            new Tier("BASE", "Base", 0, 0, BigDecimal.valueOf(1.00), List.of(), null, null),
            new Tier("SILVER", "Silver", 1, 1000, BigDecimal.valueOf(1.25), List.of(), null, null),
            new Tier("GOLD", "Gold", 2, 3000, BigDecimal.valueOf(1.50), List.of(), null, null),
            new Tier("PLATINUM", "Platinum", 3, 7000, BigDecimal.valueOf(2.00), List.of(), null, null)
    );

    @Test
    void acceptanceStefanoGold650Sts() {
        // Stefano MBR-000006 GOLD con 650 STS -> earned BASE, floor SILVER, nuovo SILVER, DOWNGRADED
        var res = EditionCloseRule.computeNext("GOLD", 650, scale);
        assertThat(res.earnedTier()).isEqualTo("BASE");
        assertThat(res.newTier()).isEqualTo("SILVER");
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.DOWNGRADED);
    }

    @Test
    void baseWithZeroStsRetained() {
        // BASE con 0 STS -> BASE RETAINED
        var res = EditionCloseRule.computeNext("BASE", 0, scale);
        assertThat(res.earnedTier()).isEqualTo("BASE");
        assertThat(res.newTier()).isEqualTo("BASE");
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.RETAINED);
    }

    @Test
    void platinumWithZeroStsDowngradedToGold() {
        // PLATINUM con 0 STS -> GOLD DOWNGRADED (floor is GOLD)
        var res = EditionCloseRule.computeNext("PLATINUM", 0, scale);
        assertThat(res.earnedTier()).isEqualTo("BASE");
        assertThat(res.newTier()).isEqualTo("GOLD");
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.DOWNGRADED);
    }

    @Test
    void memberWhoEarnedCurrentTierIsRetained() {
        // SILVER con 1500 STS -> earned SILVER, floor BASE, new SILVER, RETAINED
        var res = EditionCloseRule.computeNext("SILVER", 1500, scale);
        assertThat(res.earnedTier()).isEqualTo("SILVER");
        assertThat(res.newTier()).isEqualTo("SILVER");
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.RETAINED);
    }

    @Test
    void earnedAboveCurrentStaysCurrent() {
        // La salita non avviene in chiusura: SILVER che ha fatto 8000 STS
        // earned PLATINUM ma current è SILVER -> new SILVER, RETAINED.
        var res = EditionCloseRule.computeNext("SILVER", 8000, scale);
        assertThat(res.earnedTier()).isEqualTo("PLATINUM");
        assertThat(res.newTier()).isEqualTo("SILVER");
        assertThat(res.outcome()).isEqualTo(EditionCloseRule.Outcome.RETAINED);
    }
}
