package io.loyaltyhub.engagement.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.List;
import java.util.Optional;

/**
 * Snapshot locale del membro (docs/servizi/engagement-service.md §2, §4): nome per i segnaposto {@code member.*}, stato,
 * livello e segmenti (questi ultimi servono al pubblico dei contenuti, M6.1+). Alimentato dai fatti {@code member.*},
 * {@code tier.*}, {@code member.segment.*}; tabella {@code engagement_member_snapshot} (nome unico nell'hub, ADR-023).
 */
@Repository
public class MemberSnapshotRepository {

    public record Snapshot(String memberId, String firstName, String status, String tierCode, List<String> segments,
                           java.time.Instant registeredAt) {
    }

    private final JdbcClient jdbc;

    public MemberSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Snapshot> find(String memberId) {
        return jdbc.sql("SELECT member_id, first_name, status, tier_code, segments, registered_at FROM engagement_member_snapshot WHERE member_id = ?")
                .param(memberId)
                .query((rs, n) -> new Snapshot(rs.getString("member_id"), rs.getString("first_name"), rs.getString("status"),
                        rs.getString("tier_code"), toList(rs.getArray("segments")),
                        rs.getTimestamp("registered_at") == null ? null : rs.getTimestamp("registered_at").toInstant()))
                .optional();
    }

    /** Anagrafica da {@code member.registered/updated}: nome, stato, iscrizione; il livello resta quello noto. */
    public void upsertProfile(String memberId, String firstName, String status, java.time.Instant registeredAt) {
        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, first_name, status, registered_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET first_name = COALESCE(excluded.first_name, engagement_member_snapshot.first_name),
                          status = excluded.status,
                          registered_at = COALESCE(excluded.registered_at, engagement_member_snapshot.registered_at),
                          updated_at = now()
                        """)
                .params(memberId, firstName, status == null ? "ACTIVE" : status,
                        registeredAt == null ? null : java.sql.Timestamp.from(registeredAt))
                .update();
    }

    /** Seed: riga completa. */
    public void upsertSeed(String memberId, String firstName, String status, String tierCode, java.time.Instant registeredAt) {
        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, first_name, status, tier_code, registered_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET first_name = excluded.first_name, status = excluded.status,
                          tier_code = excluded.tier_code, registered_at = excluded.registered_at, updated_at = now()
                        """)
                .params(memberId, firstName, status == null ? "ACTIVE" : status, tierCode,
                        registeredAt == null ? null : java.sql.Timestamp.from(registeredAt))
                .update();
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status, updated_at = now()
                        """)
                .params(memberId, status)
                .update();
    }

    public void updateTier(String memberId, String tierCode) {
        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, tier_code) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET tier_code = excluded.tier_code, updated_at = now()
                        """)
                .params(memberId, tierCode)
                .update();
    }

    public void addSegment(String memberId, String segmentCode) {
        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, segments) VALUES (?, ARRAY[?::text])
                        ON CONFLICT (member_id) DO UPDATE SET
                          segments = CASE WHEN ?::text = ANY(engagement_member_snapshot.segments) THEN engagement_member_snapshot.segments
                                          ELSE array_append(engagement_member_snapshot.segments, ?::text) END,
                          updated_at = now()
                        """)
                .params(memberId, segmentCode, segmentCode, segmentCode)
                .update();
    }

    public void removeSegment(String memberId, String segmentCode) {
        jdbc.sql("""
                        UPDATE engagement_member_snapshot SET segments = array_remove(segments, ?::text), updated_at = now()
                        WHERE member_id = ?
                        """)
                .params(segmentCode, memberId)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM engagement_member_snapshot").update();
    }

    private static List<String> toList(Array array) {
        try {
            return array == null ? List.of() : List.of((String[]) array.getArray());
        } catch (Exception e) {
            return List.of();
        }
    }
}
