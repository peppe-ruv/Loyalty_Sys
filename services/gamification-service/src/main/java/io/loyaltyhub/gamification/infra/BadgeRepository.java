package io.loyaltyhub.gamification.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Badge e badge dei membri (docs/servizi/gamification-service.md §2; F-ACH-03). */
@Repository
public class BadgeRepository {

    public record Badge(String code, String name, String description, String icon, String color) {
    }

    public record MemberBadge(String badgeCode, String origin, Instant awardedAt) {
    }

    private final JdbcClient jdbc;

    public BadgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Badge> findAll() {
        return jdbc.sql("SELECT code, name, description, icon, color FROM badge ORDER BY code")
                .query((rs, n) -> new Badge(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                        rs.getString("icon"), rs.getString("color")))
                .list();
    }

    public Optional<Badge> find(String code) {
        return findAll().stream().filter(b -> b.code().equals(code)).findFirst();
    }

    public void upsert(Badge b) {
        jdbc.sql("""
                        INSERT INTO badge (code, name, description, icon, color) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, description = excluded.description,
                          icon = excluded.icon, color = excluded.color
                        """)
                .params(b.code(), b.name(), b.description(), b.icon(), b.color()).update();
    }

    /** {@code true} se il badge è nuovo per il membro (un badge si ottiene una volta; {@code effectId} idempotente). */
    public boolean award(String memberId, String badgeCode, String origin, String effectId, Instant at) {
        return jdbc.sql("""
                        INSERT INTO member_badge (member_id, badge_code, origin, awarded_at, effect_id) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """)
                .params(memberId, badgeCode, origin, Timestamp.from(at), effectId).update() == 1;
    }

    public List<MemberBadge> memberBadges(String memberId) {
        return jdbc.sql("SELECT badge_code, origin, awarded_at FROM member_badge WHERE member_id = ? ORDER BY awarded_at")
                .param(memberId)
                .query((rs, n) -> new MemberBadge(rs.getString("badge_code"), rs.getString("origin"), ContestRepository.inst(rs, "awarded_at")))
                .list();
    }

    public Map<String, Long> holders() {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("SELECT badge_code, count(*) AS n FROM member_badge GROUP BY badge_code")
                .query((rs, n) -> out.put(rs.getString("badge_code"), rs.getLong("n"))).list();
        return out;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_badge").update();
        jdbc.sql("DELETE FROM badge").update();
    }
}
