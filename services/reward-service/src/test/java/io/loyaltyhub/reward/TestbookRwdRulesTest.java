package io.loyaltyhub.reward;

import io.loyaltyhub.reward.application.PortalCatalogService;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD-STK-001…012 — stato dello stock mostrato al membro ({@code stockState}: AVAILABLE / LOW sotto il
 * 10 % / SOLD_OUT, reward-service §3; BO-10 «sotto il 10 %»). Logica pura.
 */
class TestbookRwdRulesTest {

    // TESTBOOK: ambiguo, vedi TB-RWD-STK-012
    @TestFactory
    Stream<DynamicTest> stockState() {
        return TestbookRwdCsv.rows("stock-state.csv", row -> {
            Integer total = row.is("stockTotal", "-") ? null : row.integer("stockTotal");
            Integer remaining = row.is("stockRemaining", "-") ? null : row.integer("stockRemaining");
            Reward r = new Reward("id", "RWD-TB", "Premio", null, null, null, "DIGITAL", null, "F1", "INSTANT", null,
                    total, remaining, null, List.of(), List.of(), null, null, RewardStatus.LIVE, 0, "test", null);
            assertThat(PortalCatalogService.stockState(r)).isEqualTo(row.get("expected"));
        });
    }
}
