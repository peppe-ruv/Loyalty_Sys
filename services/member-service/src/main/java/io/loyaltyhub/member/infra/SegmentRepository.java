package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.Segment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Persistenza di {@code segment} e {@code segment_member} (docs/servizi/member-service.md §2). */
@Repository
public class SegmentRepository {

    /** Membro di un segmento, con i dati minimi per l'elenco di BO-04. */
    public record SegmentMemberRow(String memberId, String firstName, String lastName, String nickname, String status,
                                   String tier, Instant enteredAt) {
    }

    /** Appartenenza di un membro (scheda {@code segments} di BO-03). */
    public record MembershipRow(String segmentId, String code, String name, String type, String status,
                                Instant enteredAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SegmentRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Segment> list(String q, String type, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM segment WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (code ILIKE ? OR name ILIKE ?)");
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            args.add(type.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY code");
        return jdbc.sql(sql.toString()).params(args).query(this::map).list();
    }

    public List<Segment> active() {
        return jdbc.sql("SELECT * FROM segment WHERE status = 'ACTIVE' ORDER BY code").query(this::map).list();
    }

    /** Per id (ULID) o per codice (docs/06 §2: nei path si accetta indifferentemente l'uno o l'altro). */
    public Optional<Segment> find(String idOrCode) {
        return jdbc.sql("SELECT * FROM segment WHERE id = ? OR code = ?").params(idOrCode, idOrCode)
                .query(this::map).optional();
    }

    /** Riga bloccata: ricalcoli concorrenti dello stesso segmento si serializzano. */
    public Optional<Segment> lock(String id) {
        return jdbc.sql("SELECT * FROM segment WHERE id = ? FOR UPDATE").param(id).query(this::map).optional();
    }

    public boolean existsByCode(String code) {
        return jdbc.sql("SELECT count(*) FROM segment WHERE code = ?").param(code).query(Long.class).single() > 0;
    }

    public void insert(Segment s) {
        jdbc.sql("""
                        INSERT INTO segment (id, code, name, description, type, criteria, status, member_count, refreshed_at,
                                             version, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, cast(? AS jsonb), ?, 0, NULL, 0, ?, ?, ?, ?)
                        """)
                .params(s.id(), s.code(), s.name(), s.description(), s.type(), json(s.criteria()), s.status(),
                        ts(s.createdAt()), ts(s.updatedAt()), s.createdBy(), s.updatedBy())
                .update();
    }

    /** Aggiorna i campi modificabili con lock ottimistico; {@code true} se la versione combaciava. */
    public boolean update(String id, int expectedVersion, String name, String description, JsonNode criteria,
                          String status, Instant now, String actor) {
        return jdbc.sql("""
                        UPDATE segment SET name = ?, description = ?, criteria = cast(? AS jsonb), status = ?,
                          version = version + 1, updated_at = ?, updated_by = ?
                        WHERE id = ? AND version = ?
                        """)
                .params(name, description, json(criteria), status, ts(now), actor, id, expectedVersion)
                .update() == 1;
    }

    public void markRefreshed(String id, int memberCount, Instant at) {
        jdbc.sql("UPDATE segment SET member_count = ?, refreshed_at = ? WHERE id = ?")
                .params(memberCount, ts(at), id).update();
    }

    // ---------- appartenenze ----------

    public Set<String> memberIds(String segmentId) {
        return new java.util.LinkedHashSet<>(jdbc.sql("SELECT member_id FROM segment_member WHERE segment_id = ? ORDER BY member_id")
                .param(segmentId).query(String.class).list());
    }

    public void addMembers(String segmentId, Collection<String> memberIds, Instant at) {
        for (String m : memberIds) {
            jdbc.sql("INSERT INTO segment_member (segment_id, member_id, entered_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(segmentId, m, ts(at)).update();
        }
    }

    public void removeMembers(String segmentId, Collection<String> memberIds) {
        for (String m : memberIds) {
            jdbc.sql("DELETE FROM segment_member WHERE segment_id = ? AND member_id = ?").params(segmentId, m).update();
        }
    }

    /** Membri di un segmento con nome e livello (proiezione), per pagina. */
    public List<SegmentMemberRow> members(String segmentId, int limit, int offset) {
        return jdbc.sql("""
                        SELECT sm.member_id, sm.entered_at, m.first_name, m.last_name, m.nickname, m.status,
                               coalesce(p.tier_code, 'BASE') AS tier_code
                        FROM segment_member sm
                        JOIN member m ON m.id = sm.member_id
                        LEFT JOIN member_projection p ON p.member_id = sm.member_id
                        WHERE sm.segment_id = ?
                        ORDER BY sm.member_id LIMIT ? OFFSET ?
                        """)
                .params(segmentId, limit, offset)
                .query((rs, n) -> new SegmentMemberRow(rs.getString("member_id"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("nickname"), rs.getString("status"),
                        rs.getString("tier_code"), instant(rs, "entered_at")))
                .list();
    }

    public long countMembers(String segmentId) {
        return jdbc.sql("SELECT count(*) FROM segment_member WHERE segment_id = ?").param(segmentId)
                .query(Long.class).single();
    }

    /** Segmenti di appartenenza di un membro (attivi e archiviati), per codice. */
    public List<MembershipRow> membershipsOf(String memberId) {
        return jdbc.sql("""
                        SELECT s.id, s.code, s.name, s.type, s.status, sm.entered_at
                        FROM segment_member sm JOIN segment s ON s.id = sm.segment_id
                        WHERE sm.member_id = ? ORDER BY s.code
                        """)
                .param(memberId)
                .query((rs, n) -> new MembershipRow(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("type"), rs.getString("status"), instant(rs, "entered_at")))
                .list();
    }

    /** Tutte le appartenenze, per codice di segmento (prima di un reset demo: servono i {@code left}). */
    public Map<String, Set<String>> allMembershipsByCode() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT s.code, sm.member_id FROM segment_member sm JOIN segment s ON s.id = sm.segment_id ORDER BY s.code, sm.member_id")
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getString("code"), k -> new java.util.LinkedHashSet<>()).add(rs.getString("member_id"));
                    return null;
                })
                .list();
        return out;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM segment_member").update();
        jdbc.sql("DELETE FROM segment").update();
    }

    // ---------- mapping ----------

    private Segment map(ResultSet rs, int n) throws SQLException {
        String criteria = rs.getString("criteria");
        return new Segment(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("type"), criteria == null ? null : mapper.readTree(criteria), rs.getString("status"),
                rs.getInt("member_count"), instant(rs, "refreshed_at"), rs.getInt("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("created_by"), rs.getString("updated_by"));
    }

    private String json(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.toString();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
