package it.iren.loyalty.engagementservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EngagementDomainTest {
    private final AchievementEngine ae = new AchievementEngine();
    private final Instant now = Instant.parse("2027-03-10T10:00:00Z");

    @Test void monthlyStreakWithEventLimitAndReset() {
        var a = new Achievement("s", "1", "s", true, "SELF_READING_SENT", List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.streak(1, Achievement.Period.MONTH, 3), new Achievement.Limit(1, Achievement.Period.MONTH), Achievement.Limit.NONE);
        var o1 = ae.apply(a, AchievementProgress.empty("m", a), "SELF_READING_SENT", Map.of(), Instant.parse("2027-01-05T10:00:00Z"));
        assertThat(ae.apply(a, o1.progress(), "SELF_READING_SENT", Map.of(), Instant.parse("2027-01-20T10:00:00Z")).progressed()).isFalse();
        var o2 = ae.apply(a, o1.progress(), "SELF_READING_SENT", Map.of(), Instant.parse("2027-02-05T10:00:00Z"));
        var o3 = ae.apply(a, o2.progress(), "SELF_READING_SENT", Map.of(), Instant.parse("2027-03-05T10:00:00Z"));
        assertThat(o2.consecutivePeriods()).isEqualTo(2); assertThat(o3.completed()).isTrue();
        assertThat(ae.apply(a, o1.progress(), "SELF_READING_SENT", Map.of(), Instant.parse("2027-03-05T10:00:00Z")).consecutivePeriods()).isEqualTo(1);
    }

    @Test void challengeMilestonesAndCompletionRule() {
        var ch = new Challenge("c", "1", "c", true, null, null, null, null, List.of(
                new Challenge.Milestone("m1", "m1", Challenge.Milestone.Kind.DIRECT, new Achievement("m1", "1", "m1", true, "A", List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.overall(1), Achievement.Limit.NONE, Achievement.Limit.NONE)),
                new Challenge.Milestone("m2", "m2", Challenge.Milestone.Kind.REFERRAL, new Achievement("m2", "1", "m2", true, "B", List.of(), Achievement.Metric.OCCURRENCES, null, Achievement.Goal.overall(1), Achievement.Limit.NONE, Achievement.Limit.NONE))),
                List.of(new Challenge.Rule("r", Challenge.Rule.Trigger.CHALLENGE_COMPLETED, 1, List.of(new Challenge.Effect("ADD_UNITS", "PREMIO", 500, null, Map.of())))), new Challenge.Limit(1, Achievement.Period.TOTAL), 2027, "b");
        var ce = new ChallengeEngine();
        var c1 = ce.apply(ch, ChallengeEngine.State.empty("m", ch), Challenge.Milestone.Kind.DIRECT, "A", Map.of(), now);
        var c2 = ce.apply(ch, c1.state(), Challenge.Milestone.Kind.REFERRAL, "B", Map.of(), now);
        assertThat(c1.completed()).isFalse(); assertThat(c2.completed()).isTrue(); assertThat(c2.effects()).hasSize(1);
        assertThat(ce.apply(ch, c2.state(), Challenge.Milestone.Kind.DIRECT, "A", Map.of(), now).progressedMilestones()).isEmpty();
    }

    @Test void leaderboardRankingTiesGroupsAndRewards() {
        var ranked = Leaderboard.rank(List.of(new Leaderboard.Score("a", "TO", 100), new Leaderboard.Score("b", "TO", 100), new Leaderboard.Score("c", "TO", 90), new Leaderboard.Score("d", "GE", 5)), 1000);
        assertThat(ranked.get("TO")).extracting(Leaderboard.Entry::rank).containsExactly(1, 1, 3);
        var lb = new Leaderboard("l", "l", true, Leaderboard.Metric.UNITS_EARNED, "PREMIO", null, null, "provincia", 1000, new Leaderboard.RewardingCycle(Achievement.Period.MONTH, List.of(new Leaderboard.RankReward(1, 1, null, "PREMIO", 1000, null))), "EVERYONE");
        assertThat(lb.rewardsFor(ranked.get("TO"))).hasSize(2);
        assertThat(new Badge("green", "Green", null, null, true, true).code()).isEqualTo("green");
        assertThat(Set.of(Leaderboard.Metric.values())).hasSize(5);
    }
}
