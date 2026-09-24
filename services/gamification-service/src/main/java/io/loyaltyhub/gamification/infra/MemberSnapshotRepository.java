package io.loyaltyhub.gamification.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Snapshot del membro per il gioco (docs/servizi/gamification-service.md §2): nickname e stato, dai fatti di member. */
@Repository
public class MemberSnapshotRepository {

    public record Snapshot(String memberId, String nickname, String status) {
    }

    private final JdbcClient jdbc;

    public MemberSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Snapshot> find(String memberId) {
        return jdbc.sql("SELECT member_id, nickname, status FROM gamification_member_snapshot WHERE member_id = ?")
                .param(memberId)
                .query((rs, n) -> new Snapshot(rs.getString("member_id"), rs.getString("nickname"), rs.getString("status")))
                .optional();
    }

    public void upsert(String memberId, String nickname, String status) {
        jdbc.sql("""
                        INSERT INTO gamification_member_snapshot (member_id, nickname, status) VALUES (?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET nickname = COALESCE(excluded.nickname, gamification_member_snapshot.nickname),
                          status = excluded.status, updated_at = now()
                        """)
                .params(memberId, nickname, status == null ? "ACTIVE" : status)
                .update();
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("""
                        INSERT INTO gamification_member_snapshot (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status, updated_at = now()
                        """)
                .params(memberId, status)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM gamification_member_snapshot").update();
    }
}
