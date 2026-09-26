package io.loyaltyhub.campaign.infra;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.engine.MemberSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/** Snapshot locale dei membri (docs/servizi/campaign-service.md §2, §4): alimentato dai fatti member e tier. */
@Repository
public class MemberSnapshotRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MemberSnapshotRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<MemberSnapshot> findById(String memberId) {
        return jdbc.sql("""
                        SELECT member_id, status, tier_code, segments, labels, attributes::text AS attributes,
                               registered_at, birth_date
                        FROM member_snapshot WHERE member_id = ?
                        """)
                .param(memberId)
                .query((rs, n) -> new MemberSnapshot(
                        rs.getString("member_id"), rs.getString("status"), rs.getString("tier_code"),
                        TextArrays.toList(rs.getArray("segments")), TextArrays.toList(rs.getArray("labels")),
                        json(rs.getString("attributes")),
                        rs.getTimestamp("registered_at") == null ? null : rs.getTimestamp("registered_at").toInstant(),
                        rs.getObject("birth_date", LocalDate.class)))
                .optional();
    }

    public boolean exists(String memberId) {
        return jdbc.sql("SELECT count(*) FROM member_snapshot WHERE member_id = ?")
                .param(memberId)
                .query(Long.class)
                .single() > 0;
    }

    public void upsertIdentity(String memberId, String status, String tier, java.time.Instant registeredAt,
                               LocalDate birthDate, String attributesJson) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, status, tier_code, registered_at, birth_date, attributes)
                        VALUES (?, ?, coalesce(?, 'BASE'), ?, ?, cast(coalesce(?, '{}') AS jsonb))
                        ON CONFLICT (member_id) DO UPDATE SET
                          status = excluded.status,
                          tier_code = coalesce(excluded.tier_code, member_snapshot.tier_code),
                          registered_at = coalesce(excluded.registered_at, member_snapshot.registered_at),
                          birth_date = coalesce(excluded.birth_date, member_snapshot.birth_date),
                          attributes = excluded.attributes
                        """)
                .params(memberId, status, tier,
                        registeredAt == null ? null : java.sql.Timestamp.from(registeredAt),
                        birthDate, attributesJson)
                .update();
    }

    /** Etichette dallo snapshot completo di {@code member.registered/updated} (docs/05 EVT-FACT-01/02). */
    public void updateLabels(String memberId, java.util.List<String> labels) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, labels) VALUES (?, ?::text[])
                        ON CONFLICT (member_id) DO UPDATE SET labels = excluded.labels
                        """)
                .params(memberId, TextArrays.literal(labels)).update();
    }

    /** {@code member.segment.entered} (EVT-FACT-06): aggiunge il segmento se non c'è già (idempotente). */
    public void addSegment(String memberId, String segmentCode) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, segments) VALUES (?, ARRAY[?::text])
                        ON CONFLICT (member_id) DO UPDATE SET segments = CASE
                          WHEN ?::text = ANY(member_snapshot.segments) THEN member_snapshot.segments
                          ELSE array_append(member_snapshot.segments, ?::text) END
                        """)
                .params(memberId, segmentCode, segmentCode, segmentCode).update();
    }

    /** {@code member.segment.left} (EVT-FACT-07). */
    public void removeSegment(String memberId, String segmentCode) {
        jdbc.sql("UPDATE member_snapshot SET segments = array_remove(segments, ?::text) WHERE member_id = ?")
                .params(segmentCode, memberId).update();
    }

    public void updateStatus(String memberId, String status) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET status = excluded.status
                        """)
                .params(memberId, status).update();
    }

    /**
     * Anonimizzazione (F-MBR-05, M7.5): stato {@code ANONYMIZED}, data di nascita e attributi personalizzati cancellati.
     * Livello, segmenti ed etichette restano (statistiche, docs/03 §2).
     */
    public void erasePersonal(String memberId) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, status) VALUES (?, 'ANONYMIZED')
                        ON CONFLICT (member_id) DO UPDATE SET status = 'ANONYMIZED', birth_date = NULL,
                          attributes = '{}'::jsonb
                        """)
                .param(memberId).update();
    }

    public void updateTier(String memberId, String tier) {
        jdbc.sql("""
                        INSERT INTO member_snapshot (member_id, status, tier_code) VALUES (?, 'ACTIVE', ?)
                        ON CONFLICT (member_id) DO UPDATE SET tier_code = excluded.tier_code
                        """)
                .params(memberId, tier).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member_snapshot").update();
    }

    private JsonNode json(String text) {
        return text == null ? mapper.createObjectNode() : mapper.readTree(text);
    }
}
