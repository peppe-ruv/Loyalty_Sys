package it.iren.loyalty.engagementservice.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.engagementservice.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Consuma le azioni (D08) e aggiorna achievement, challenge e punteggi delle classifiche del membro; ogni avanzamento o
 * completamento diventa un'azione interna (ACHIEVEMENT_PROGRESSED, ACHIEVEMENT_COMPLETED, CHALLENGE_PROGRESSED,
 * CHALLENGE_COMPLETED, MISSION_COMPLETED per le missioni del programma) premiata dalle campagne; gli effetti diretti
 * delle regole di challenge sono applicati tramite le stesse azioni (attributo {@code effects}).
 * Stato per membro in JSONB con lock di riga: nessun doppio conteggio con consumer concorrenti sulla stessa partizione.
 */
@Service
public class EngagementService {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Definitions defs;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AchievementEngine achievements = new AchievementEngine();
    private final ChallengeEngine challenges = new ChallengeEngine();
    private final it.iren.loyalty.common.metrics.LoyaltyMetrics metrics;

    public EngagementService(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, Definitions defs, it.iren.loyalty.common.metrics.LoyaltyMetrics metrics) { this.jdbc = jdbc; this.kafka = kafka; this.defs = defs; this.metrics = metrics; }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, groupId = "engagement-service", concurrency = "${engagement.consumer.concurrency:4}")
    public void onAction(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(event.getType())) return;
        var action = CanonicalEvents.data(event, RewardingAction.class);
        if (action.isReversal() || action.actionType().startsWith("ACHIEVEMENT_") || action.actionType().startsWith("CHALLENGE_")) return;
        apply(CanonicalEvents.memberId(event), action);
    }

    @Transactional
    public void apply(String memberId, RewardingAction action) {
        Instant at = action.occurredAt() == null ? Instant.now() : action.occurredAt();
        Map<String, Object> attrs = action.attributes() == null ? Map.of() : action.attributes();
        for (Achievement a : defs.achievements()) {
            if (!a.active() || !a.actionType().equals(action.actionType())) continue;
            AchievementProgress p = load("achievement_progress", memberId, a.id(), AchievementProgress.class, AchievementProgress.empty(memberId, a));
            if (!p.version().equals(a.version())) p = AchievementProgress.empty(memberId, a);
            var o = achievements.apply(a, p, action.actionType(), attrs, at);
            if (!o.progressed()) continue;
            save("achievement_progress", memberId, a.id(), o.progress(), o.progress().completedCount());
            emit(memberId, "ACHIEVEMENT_PROGRESSED", "achievement:" + a.id() + ":" + memberId + ":" + action.idempotencyKey(), a.id(), at,
                    Map.of("achievementId", a.id(), "currentPeriodValue", o.currentPeriodValue(), "consecutivePeriods", o.consecutivePeriods(), "completedCount", o.progress().completedCount()));
            if (o.completed()) metrics.achievementCompleted(a.id());
            if (o.completed()) emit(memberId, "ACHIEVEMENT_COMPLETED", "achievement:" + a.id() + ":" + memberId + ":done:" + o.progress().completedCount(), a.id(), at,
                    Map.of("achievementId", a.id(), "completedCount", o.progress().completedCount()));
        }
        String referrerId = referrerOf(memberId);
        for (Challenge c : defs.challenges()) {
            if (!c.isActiveAt(at)) continue;
            applyChallenge(c, memberId, Challenge.Milestone.Kind.DIRECT, action, attrs, at);
            if (referrerId != null) applyChallenge(c, referrerId, Challenge.Milestone.Kind.REFERRAL, action, attrs, at);
        }
        for (Leaderboard lb : defs.leaderboards()) {
            if (!lb.active() || (lb.startsAt() != null && at.isBefore(lb.startsAt())) || (lb.endsAt() != null && !at.isBefore(lb.endsAt()))) continue;
            double delta = switch (lb.metric()) {
                case TRANSACTIONS_COUNT -> EventTypes.ACTION_TRANSACTION.equals(action.actionType()) ? 1 : 0;
                case TRANSACTIONS_VALUE -> EventTypes.ACTION_TRANSACTION.equals(action.actionType()) ? num(attrs.get(EventTypes.ATTR_AMOUNT_EUR)) : 0;
                case CUSTOM_EVENTS_COUNT -> lb.reference() == null || lb.reference().equals(action.actionType()) ? 1 : 0;
                case ACHIEVEMENT_PROGRESS -> "ACHIEVEMENT_PROGRESSED".equals(action.actionType()) && lb.reference().equals(action.externalRef()) ? 1 : 0;
                case UNITS_EARNED -> 0; // alimentata dal consumer dei movimenti (onMovement)
            };
            if (delta != 0) addScore(lb, memberId, delta);
        }
    }

    private void applyChallenge(Challenge c, String memberId, Challenge.Milestone.Kind kind, RewardingAction action, Map<String, Object> attrs, Instant at) {
        ChallengeEngine.State s = load("challenge_state", memberId, c.id(), ChallengeEngine.State.class, ChallengeEngine.State.empty(memberId, c));
        var o = challenges.apply(c, s, kind, action.actionType(), attrs, at);
        if (o.progressedMilestones().isEmpty()) return;
        save("challenge_state", memberId, c.id(), o.state(), o.state().completedCount());
        String base = "challenge:" + c.id() + ":" + memberId + ":" + action.idempotencyKey();
        Map<String, Object> data = new HashMap<>(Map.of("challengeId", c.id(), "milestones", o.progressedMilestones(), "completedCount", o.state().completedCount()));
        if (!o.effects().isEmpty()) data.put("effects", o.effects());
        emit(memberId, "CHALLENGE_PROGRESSED", base, c.id(), at, data);
        if (o.completed()) {
            metrics.challengeCompleted(c.id());
            emit(memberId, c.programYear() != null ? EventTypes.ACTION_MISSION_COMPLETED : "CHALLENGE_COMPLETED", base + ":done", c.id(), at, data);
            if (c.badgeCode() != null) grantBadge(memberId, c.badgeCode(), "challenge:" + c.id() + ":" + o.state().completedCount());
        }
    }

    @KafkaListener(topics = EventTypes.TOPIC_MOVEMENTS, groupId = "engagement-service-movements")
    public void onMovement(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(event, Map.class);
        long amount = ((Number) m.get("amount")).longValue();
        if (amount <= 0 || !"EARN".equals(m.get("kind"))) return;
        String memberId = CanonicalEvents.memberId(event);
        for (Leaderboard lb : defs.leaderboards())
            if (lb.active() && lb.metric() == Leaderboard.Metric.UNITS_EARNED && (lb.reference() == null || lb.reference().equals(m.get("currency")))) addScore(lb, memberId, amount);
    }

    @Transactional
    public Badge.Grant grantBadge(String memberId, String badgeCode, String grantKey) {
        Badge b = defs.badges().stream().filter(x -> x.code().equals(badgeCode)).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown badge " + badgeCode));
        if (!b.active()) throw new IllegalStateException("badge inactive");
        if (jdbc.update("INSERT INTO engagementservice.badge_grant_key(grant_key) VALUES (?) ON CONFLICT DO NOTHING", grantKey) == 0) return badgeOf(memberId, badgeCode).orElseThrow();
        Instant now = Instant.now();
        var existing = badgeOf(memberId, badgeCode);
        Badge.Grant g = existing.map(e -> e.grantAgain(now, grantKey, b.stackable())).orElse(new Badge.Grant(memberId, badgeCode, 1, now, now, grantKey));
        jdbc.update("INSERT INTO engagementservice.member_badge(member_id, badge_code, completed_count, first_granted_at, last_granted_at, source) VALUES (?,?,?,?,?,?) ON CONFLICT (member_id, badge_code) DO UPDATE SET completed_count = EXCLUDED.completed_count, last_granted_at = EXCLUDED.last_granted_at, source = EXCLUDED.source",
                memberId, badgeCode, g.completedCount(), Timestamp.from(g.firstGrantedAt()), Timestamp.from(g.lastGrantedAt()), grantKey);
        metrics.badgeGranted(badgeCode);
        emit(memberId, "BADGE_GRANTED", "badge:" + grantKey, badgeCode, now, Map.of("badgeCode", badgeCode, "completedCount", g.completedCount()));
        return g;
    }

    public Optional<Badge.Grant> badgeOf(String memberId, String code) {
        return jdbc.query("SELECT completed_count, first_granted_at, last_granted_at, source FROM engagementservice.member_badge WHERE member_id = ? AND badge_code = ?",
                rs -> rs.next() ? Optional.of(new Badge.Grant(memberId, code, rs.getInt(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getString(4))) : Optional.<Badge.Grant>empty(), memberId, code);
    }

    private void addScore(Leaderboard lb, String memberId, double delta) {
        jdbc.update("INSERT INTO engagementservice.leaderboard_score(leaderboard_id, member_id, value, updated_at) VALUES (?,?,?,now()) ON CONFLICT (leaderboard_id, member_id) DO UPDATE SET value = engagementservice.leaderboard_score.value + EXCLUDED.value, updated_at = now()",
                lb.id(), memberId, delta);
    }

    private String referrerOf(String memberId) {
        try { return jdbc.query("SELECT referrer_id FROM engagementservice.member_referrer WHERE member_id = ?", rs -> rs.next() ? rs.getString(1) : null, memberId); }
        catch (Exception e) { return null; }
    }

    <T> T load(String table, String memberId, String defId, Class<T> type, T empty) {
        String s = jdbc.query("SELECT state FROM engagementservice." + table + " WHERE member_id = ? AND definition_id = ? FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, memberId, defId);
        try { return s == null ? empty : json.readValue(s, type); } catch (Exception e) { return empty; }
    }

    void save(String table, String memberId, String defId, Object state, int completedCount) {
        try {
            jdbc.update("INSERT INTO engagementservice." + table + "(member_id, definition_id, state, completed_count, updated_at) VALUES (?,?,?::jsonb,?,now()) ON CONFLICT (member_id, definition_id) DO UPDATE SET state = EXCLUDED.state, completed_count = EXCLUDED.completed_count, updated_at = now()",
                    memberId, defId, json.writeValueAsString(state), completedCount);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void emit(String memberId, String type, String key, String ref, Instant at, Map<String, Object> attrs) {
        var a = new RewardingAction(type, key.replaceAll("[^A-Za-z0-9._:-]", "-"), ref, at, null, attrs);
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.ACTION_V1, "urn:iren:loyalty:engagement", "member:" + memberId, a)));
    }

    private static double num(Object o) { try { return o == null ? 0 : Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; } }
}
