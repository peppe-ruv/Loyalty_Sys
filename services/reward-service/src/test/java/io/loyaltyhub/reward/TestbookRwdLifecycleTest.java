package io.loyaltyhub.reward;

import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.approval.GovernedTransitions;
import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.reward.domain.RewardStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD-LCY-001…099 — ciclo di vita del premio: ogni stato × ogni azione con la policy {@code REWARD}
 * (approvazione LEGAL sempre, docs/06 §7), matrice dei ruoli, approvazione spenta. Logica pura: la stessa chiamata di
 * {@code CatalogAdminService#transition} ({@link GovernedTransitions} con {@code policy.forReward()}).
 */
class TestbookRwdLifecycleTest {

    // TESTBOOK: scelta da decidere, vedi Q-R10 (TB-RWD-LCY-095, -098, -099)
    @TestFactory
    Stream<DynamicTest> transition() {
        return TestbookRwdCsv.rows("lifecycle.csv", row -> {
            ApprovalPolicy policy = new ApprovalPolicy(Boolean.parseBoolean(row.get("approval")), 100_000);
            String actor = row.get("actor");
            String comment = row.get("comment");
            String outcome;
            try {
                ApprovalStatus next = GovernedTransitions.next(ApprovalStatus.valueOf(row.get("from")),
                        GovernedTransitions.parse(row.get("action")), policy.forReward(), policy.enabled(),
                        ActorContext.parse(actor.isEmpty() ? null : actor).role(), comment.isEmpty() ? null : comment);
                // lo stato deve esistere anche nel ciclo del premio
                outcome = RewardStatus.valueOf(next.name()).name();
            } catch (LhException e) {
                outcome = e.status().value() + ":" + e.code();
            }
            assertThat(outcome).isEqualTo(row.get("expected"));
        });
    }
}
