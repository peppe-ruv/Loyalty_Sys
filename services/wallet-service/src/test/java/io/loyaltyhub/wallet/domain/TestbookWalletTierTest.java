package io.loyaltyhub.wallet.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestbookWalletTierTest {

    private final List<Tier> scale = List.of(
            new Tier("BASE", "Base", 0, 0, BigDecimal.valueOf(1.00), List.of(), null, null),
            new Tier("SILVER", "Silver", 1, 1000, BigDecimal.valueOf(1.25), List.of(), null, null),
            new Tier("GOLD", "Gold", 2, 3000, BigDecimal.valueOf(1.50), List.of(), null, null),
            new Tier("PLATINUM", "Platinum", 3, 7000, BigDecimal.valueOf(2.00), List.of(), null, null)
    );

    @ParameterizedTest(name = "[{0}] {1} con {2} STS -> {5}")
    @CsvFileSource(resources = "/testbook/wallet/tiers.csv", numLinesToSkip = 1)
    @DisplayName("F-TIER-02, F-TIER-04: Salita e discesa livelli")
    void tierRules(String id, String currentTier, long periodSts, String operation,
                   String expectedEarned, String expectedNewTier, String expectedOutcome) {

        if ("CLOSE".equals(operation)) {
            // Chiusura edizione usa la logica pura in EditionCloseRule.
            EditionCloseRule.Result next = EditionCloseRule.computeNext(currentTier, periodSts, scale);

            assertThat(next.earnedTier()).isEqualTo(expectedEarned);
            assertThat(next.newTier()).isEqualTo(expectedNewTier);
            assertThat(next.outcome().name()).isEqualTo(expectedOutcome);
        }
    }
}
