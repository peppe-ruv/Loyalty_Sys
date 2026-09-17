package it.iren.loyalty.engagementservice.domain;

import java.time.Instant;
import java.util.*;

/**
 * Avanza una challenge (RF-91) applicando l'evento alle milestone compatibili; quando tutte le milestone sono
 * completate almeno una volta nel ciclo corrente la challenge è completata, il conteggio sale e le milestone ripartono.
 * Restituisce gli effetti delle regole scattate; i limiti di completamento valgono per periodo o in totale.
 */
public class ChallengeEngine {
    public record State(String memberId, String challengeId, Map<String, AchievementProgress> milestones, int completedCount, Instant lastCompletedAt) {
        public static State empty(String memberId, Challenge c) {
            Map<String, AchievementProgress> m = new LinkedHashMap<>();
            for (var ms : c.milestones()) m.put(ms.id(), AchievementProgress.empty(memberId, ms.definition()));
            return new State(memberId, c.id(), m, 0, null);
        }
    }
    public record Outcome(State state, List<String> progressedMilestones, boolean completed, List<Challenge.Effect> effects) {}

    private final AchievementEngine achievements = new AchievementEngine();

    public Outcome apply(Challenge c, State s, Challenge.Milestone.Kind actorKind, String actionType, Map<String, Object> attrs, Instant at) {
        if (!c.isActiveAt(at) || !c.availability().admits(at)) return new Outcome(s, List.of(), false, List.of());
        if (c.completionLimit().max() > 0 && (c.completionLimit().period() == Achievement.Period.TOTAL ? s.completedCount() >= c.completionLimit().max()
                : s.lastCompletedAt() != null && !s.lastCompletedAt().isBefore(AchievementEngine.periodStart(at, c.completionLimit().period()))))
            return new Outcome(s, List.of(), false, List.of());
        Map<String, AchievementProgress> next = new LinkedHashMap<>(s.milestones());
        List<String> progressed = new ArrayList<>();
        List<Challenge.Effect> effects = new ArrayList<>();
        for (var ms : c.milestones()) {
            if (ms.kind() != actorKind) continue;
            var o = achievements.apply(ms.definition(), next.get(ms.id()), actionType, attrs, at);
            if (o.progressed()) { next.put(ms.id(), o.progress()); progressed.add(ms.id()); }
        }
        if (progressed.isEmpty()) return new Outcome(s, List.of(), false, List.of());
        boolean allDone = c.milestones().stream().allMatch(ms -> next.get(ms.id()).completedCount() > s.milestones().get(ms.id()).completedCount() || completedThisCycle(next.get(ms.id()), s.lastCompletedAt()));
        int completedCount = s.completedCount() + (allDone ? 1 : 0);
        for (var r : c.rules()) {
            boolean fires = r.trigger() == Challenge.Rule.Trigger.MILESTONE_PROGRESSED || allDone;
            if (fires && (r.maxCompletionCount() == null || completedCount <= r.maxCompletionCount())) effects.addAll(r.effects());
        }
        State ns = new State(s.memberId(), s.challengeId(), allDone ? resetMilestones(c, s.memberId()) : next, completedCount, allDone ? at : s.lastCompletedAt());
        return new Outcome(ns, progressed, allDone, effects);
    }

    private static boolean completedThisCycle(AchievementProgress p, Instant cycleStart) {
        return p.lastCompletedAt() != null && (cycleStart == null || p.lastCompletedAt().isAfter(cycleStart));
    }

    private static Map<String, AchievementProgress> resetMilestones(Challenge c, String memberId) {
        Map<String, AchievementProgress> m = new LinkedHashMap<>();
        for (var ms : c.milestones()) m.put(ms.id(), AchievementProgress.empty(memberId, ms.definition()));
        return m;
    }
}
