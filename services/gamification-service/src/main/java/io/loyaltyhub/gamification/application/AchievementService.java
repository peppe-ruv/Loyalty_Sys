package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Progresso degli obiettivi (docs/03 §8, docs/servizi/gamification-service.md §5; F-ACH-01, F-ACH-02): ogni azione dei
 * tipi osservati avanza il progresso del periodo; {@code achievement.progressed} solo al cambio di valore;
 * al traguardo {@code achievement.completed} (+ badge collegato). Non ripetibile → una volta per sempre; ripetibile →
 * una per periodo. Completato un periodo, le azioni successive nello stesso periodo non riemettono nulla.
 * Le azioni interne contano solo se elencate nei tipi osservati (niente cicli impliciti).
 */
@Service
public class AchievementService {

    private final AchievementRepository achievements;
    private final BadgeRepository badges;
    private final BadgeService badgeService;
    private final MemberSnapshotRepository members;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public AchievementService(AchievementRepository achievements, BadgeRepository badges, BadgeService badgeService,
                              MemberSnapshotRepository members, LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.achievements = achievements;
        this.badges = badges;
        this.badgeService = badgeService;
        this.members = members;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void onAction(LhEvent<JsonNode> action) {
        String memberId = action.memberId();
        if (memberId == null || action.type() == null || !action.type().startsWith(LhEventTypes.Action.PREFIX)) {
            return;
        }
        boolean inactive = members.find(memberId).map(s -> !"ACTIVE".equals(s.status())).orElse(false);
        if (inactive) {
            return;
        }
        String shortType = action.type().substring(LhEventTypes.Action.PREFIX.length());
        Instant at = action.time() != null ? action.time() : clock.instant();
        for (Achievement a : achievements.findActiveFor(shortType)) {
            if (!AchievementRules.matches(a.filter(), action.data())) {
                continue;
            }
            if (!a.repeatable() && achievements.completedAny(a.id(), memberId)) {
                continue;
            }
            String periodKey = AchievementRules.periodKey(a.period(), at);
            AchievementRepository.ProgressRow row = achievements.lockProgress(a.id(), memberId, periodKey);
            if (row.completedAt() != null) {
                continue;
            }
            AchievementRules.Progress next = AchievementRules.advance(a, row.progress(), shortType, action.data(), at);
            if (next.equals(row.progress())) {
                continue;
            }
            boolean completed = next.value() >= a.target();
            achievements.saveProgress(row, next, completed ? clock.instant() : null);
            if (next.value() != row.value()) {
                Map<String, Object> progressed = new LinkedHashMap<>();
                progressed.put("achievementCode", a.code());
                progressed.put("periodKey", periodKey);
                progressed.put("value", Math.min(next.value(), a.target()));
                progressed.put("target", a.target());
                outbox.write(events.childOf(action, LhEventTypes.Fact.ACHIEVEMENT_PROGRESSED, progressed));
            }
            if (completed) {
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("achievementCode", a.code());
                done.put("achievementName", a.name());
                done.put("periodKey", periodKey);
                outbox.write(events.childOf(action, LhEventTypes.Fact.ACHIEVEMENT_COMPLETED, done));
                if (a.badgeCode() != null) {
                    badges.find(a.badgeCode()).ifPresent(b -> badgeService.award(memberId, b, "ACHIEVEMENT", null, action));
                }
            }
        }
    }
}
