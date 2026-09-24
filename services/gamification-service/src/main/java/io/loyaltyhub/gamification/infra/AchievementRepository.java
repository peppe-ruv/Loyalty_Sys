package io.loyaltyhub.gamification.infra;

import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Obiettivi e progressi (docs/servizi/gamification-service.md §2). */
@Repository
public class AchievementRepository {

    private static final String COLUMNS = """
            id, code, name, description, icon, action_types, filter::text AS filter, metric, sum_field, streak_unit, target,
            period, repeatable, badge_code, status""";

    public record ProgressRow(String achievementId, String memberId, String periodKey, long value, List<String> distinctSeen,
                              String lastUnitKey, Instant completedAt) {
        public AchievementRules.Progress progress() {
            return new AchievementRules.Progress(value, distinctSeen, lastUnitKey);
        }
    }

    public record Stats(long completions, long inProgress) {
        public static final Stats NONE = new Stats(0, 0);
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AchievementRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Achievement> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM achievement ORDER BY code").query(this::map).list();
    }

    public List<Achievement> findActiveFor(String actionType) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM achievement WHERE status = 'ACTIVE' AND ? = ANY(action_types) ORDER BY code")
                .param(actionType).query(this::map).list();
    }

    public Optional<Achievement> find(String idOrCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM achievement WHERE id = ? OR code = ?").params(idOrCode, idOrCode)
                .query(this::map).optional();
    }

    public void insert(Achievement a) {
        jdbc.sql("""
                        INSERT INTO achievement (id, code, name, description, icon, action_types, filter, metric, sum_field,
                          streak_unit, target, period, repeatable, badge_code, status)
                        VALUES (?, ?, ?, ?, ?, ?::text[], ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(a.id(), a.code(), a.name(), a.description(), a.icon(), TextArrays.literal(a.actionTypes()), json(a.filter()),
                        a.metric(), a.sumField(), a.streakUnit(), a.target(), a.period(), a.repeatable(), a.badgeCode(), a.status())
                .update();
    }

    public void update(Achievement a) {
        jdbc.sql("""
                        UPDATE achievement SET name = ?, description = ?, icon = ?, action_types = ?::text[], filter = ?::jsonb,
                          metric = ?, sum_field = ?, streak_unit = ?, target = ?, period = ?, repeatable = ?, badge_code = ?,
                          status = ?
                        WHERE id = ?
                        """)
                .params(a.name(), a.description(), a.icon(), TextArrays.literal(a.actionTypes()), json(a.filter()), a.metric(),
                        a.sumField(), a.streakUnit(), a.target(), a.period(), a.repeatable(), a.badgeCode(), a.status(), a.id())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM achievement_progress").update();
        jdbc.sql("DELETE FROM achievement").update();
    }

    // ---------- progressi ----------

    /** Riga di progresso bloccata (creata vuota se manca): gli aggiornamenti dello stesso membro si serializzano. */
    public ProgressRow lockProgress(String achievementId, String memberId, String periodKey) {
        jdbc.sql("""
                        INSERT INTO achievement_progress (achievement_id, member_id, period_key) VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """)
                .params(achievementId, memberId, periodKey).update();
        return jdbc.sql("""
                        SELECT achievement_id, member_id, period_key, value, distinct_seen, last_unit_key, completed_at
                        FROM achievement_progress WHERE achievement_id = ? AND member_id = ? AND period_key = ? FOR UPDATE
                        """)
                .params(achievementId, memberId, periodKey).query(AchievementRepository::mapProgress).single();
    }

    public void saveProgress(ProgressRow p, AchievementRules.Progress next, Instant completedAt) {
        jdbc.sql("""
                        UPDATE achievement_progress SET value = ?, distinct_seen = ?::text[], last_unit_key = ?, completed_at = ?
                        WHERE achievement_id = ? AND member_id = ? AND period_key = ?
                        """)
                .params(next.value(), TextArrays.literal(next.distinctSeen()), next.lastUnitKey(),
                        completedAt == null ? null : Timestamp.from(completedAt), p.achievementId(), p.memberId(), p.periodKey())
                .update();
    }

    public boolean completedAny(String achievementId, String memberId) {
        return jdbc.sql("SELECT count(*) FROM achievement_progress WHERE achievement_id = ? AND member_id = ? AND completed_at IS NOT NULL")
                .params(achievementId, memberId).query(Long.class).single() > 0;
    }

    public List<ProgressRow> memberProgress(String memberId) {
        return jdbc.sql("""
                        SELECT achievement_id, member_id, period_key, value, distinct_seen, last_unit_key, completed_at
                        FROM achievement_progress WHERE member_id = ?
                        """)
                .param(memberId).query(AchievementRepository::mapProgress).list();
    }

    /** Completamenti e membri in corso (valore > 0, non completato) per obiettivo (BO-15). */
    public Map<String, Stats> stats() {
        Map<String, Stats> out = new HashMap<>();
        jdbc.sql("""
                        SELECT achievement_id, count(*) FILTER (WHERE completed_at IS NOT NULL) AS done,
                          count(DISTINCT member_id) FILTER (WHERE completed_at IS NULL AND value > 0) AS running
                        FROM achievement_progress GROUP BY achievement_id
                        """)
                .query((rs, n) -> out.put(rs.getString("achievement_id"), new Stats(rs.getLong("done"), rs.getLong("running"))))
                .list();
        return out;
    }

    /** Seed: progresso già avviato (o completato) per un membro. */
    public void seedProgress(String achievementId, String memberId, String periodKey, long value, List<String> distinct,
                             String lastUnitKey, Instant completedAt) {
        jdbc.sql("""
                        INSERT INTO achievement_progress (achievement_id, member_id, period_key, value, distinct_seen, last_unit_key,
                          completed_at) VALUES (?, ?, ?, ?, ?::text[], ?, ?)
                        """)
                .params(achievementId, memberId, periodKey, value, TextArrays.literal(distinct), lastUnitKey,
                        completedAt == null ? null : Timestamp.from(completedAt))
                .update();
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : mapper.writeValueAsString(node);
    }

    private Achievement map(ResultSet rs, int n) throws SQLException {
        String filter = rs.getString("filter");
        return new Achievement(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("icon"), TextArrays.toList(rs.getArray("action_types")), filter == null ? null : mapper.readTree(filter),
                rs.getString("metric"), rs.getString("sum_field"), rs.getString("streak_unit"), rs.getLong("target"),
                rs.getString("period"), rs.getBoolean("repeatable"), rs.getString("badge_code"), rs.getString("status"));
    }

    private static ProgressRow mapProgress(ResultSet rs, int n) throws SQLException {
        return new ProgressRow(rs.getString("achievement_id"), rs.getString("member_id"), rs.getString("period_key"),
                rs.getLong("value"), TextArrays.toList(rs.getArray("distinct_seen")), rs.getString("last_unit_key"),
                ContestRepository.inst(rs, "completed_at"));
    }
}
