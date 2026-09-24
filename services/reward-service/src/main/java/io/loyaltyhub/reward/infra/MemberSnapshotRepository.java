package io.loyaltyhub.reward.infra;

import io.loyaltyhub.reward.domain.MemberSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Snapshot locale del membro (docs/servizi/reward-service.md §2), aggiornato dai fatti di member e wallet. */
@Repository
public class MemberSnapshotRepository {

    private final JdbcClient jdbc;

    public MemberSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberSnapshot> find(String memberId) {
        return jdbc.sql("SELECT member_id, status, tier_code, segments, first_name, last_name FROM reward_member_snapshot WHERE member_id = ?")
                .param(memberId)
                .query((rs, n) -> new MemberSnapshot(rs.getString("member_id"), rs.getString("status"),
                        rs.getString("tier_code"), TextArrays.toList(rs.getArray("segments")),
                        rs.getString("first_name"), rs.getString("last_name")))
                .optional();
    }

    /** Anagrafica e stato (member.registered / member.updated); il tier resta quello già noto. */
    /** Riga del membro bloccata: le richieste dello stesso membro si serializzano (limite per membro). */
    public Optional<MemberSnapshot> lock(String memberId) {
        return jdbc.sql("SELECT member_id FROM reward_member_snapshot WHERE member_id = ? FOR UPDATE")
                .param(memberId).query(String.class).optional().flatMap(this::find);
    }

    public void upsertProfile(String memberId, String status, String firstName, String lastName) {
        jdbc.sql("""
                        INSERT INTO reward_member_snapshot (member_id, status, first_name, last_name) VALUES (?, ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status,
                          first_name = coalesce(excluded.first_name, reward_member_snapshot.first_name),
                          last_name = coalesce(excluded.last_name, reward_member_snapshot.last_name), updated_at = now()
                        """)
                .params(memberId, status, firstName, lastName).update();
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("""
                        INSERT INTO reward_member_snapshot (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status, updated_at = now()
                        """)
                .params(memberId, status).update();
    }

    /** {@code member.segment.entered} (EVT-FACT-06): aggiunge il segmento se non c'è già (idempotente). */
    public void addSegment(String memberId, String segmentCode) {
        jdbc.sql("""
                        INSERT INTO reward_member_snapshot (member_id, segments) VALUES (?, ARRAY[?::text])
                        ON CONFLICT (member_id) DO UPDATE SET segments = CASE
                          WHEN ?::text = ANY(reward_member_snapshot.segments) THEN reward_member_snapshot.segments
                          ELSE array_append(reward_member_snapshot.segments, ?::text) END, updated_at = now()
                        """)
                .params(memberId, segmentCode, segmentCode, segmentCode).update();
    }

    /** {@code member.segment.left} (EVT-FACT-07). */
    public void removeSegment(String memberId, String segmentCode) {
        jdbc.sql("UPDATE reward_member_snapshot SET segments = array_remove(segments, ?::text), updated_at = now() WHERE member_id = ?")
                .params(segmentCode, memberId).update();
    }

    public void updateTier(String memberId, String tier) {
        jdbc.sql("""
                        INSERT INTO reward_member_snapshot (member_id, tier_code) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET tier_code = excluded.tier_code, updated_at = now()
                        """)
                .params(memberId, tier).update();
    }

    public void seed(String memberId, String status, String tier, String firstName, String lastName) {
        jdbc.sql("""
                        INSERT INTO reward_member_snapshot (member_id, status, tier_code, first_name, last_name) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status, tier_code = excluded.tier_code,
                          first_name = excluded.first_name, last_name = excluded.last_name, updated_at = now()
                        """)
                .params(memberId, status, tier, firstName, lastName).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM reward_member_snapshot").update();
    }
}
