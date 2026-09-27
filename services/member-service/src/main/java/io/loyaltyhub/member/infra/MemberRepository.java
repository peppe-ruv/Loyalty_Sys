package io.loyaltyhub.member.infra;

import io.loyaltyhub.common.sql.SqlColumn;
import io.loyaltyhub.common.sql.SqlWhere;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistenza dell'anagrafica {@code member} (docs/servizi/member-service.md §2, §3). */
@Repository
public class MemberRepository {

    private final JdbcClient jdbc;

    public MemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Prossimo id anagrafico dalla sequenza: {@code MBR-######}. */
    public String nextId() {
        long n = jdbc.sql("SELECT nextval('member_id_seq')").query(Long.class).single();
        return String.format("MBR-%06d", n);
    }

    public Optional<Member> findById(String id) {
        return jdbc.sql("SELECT * FROM member WHERE id = ?").param(id)
                .query(MemberRepository::map).optional();
    }

    public boolean existsByEmail(String email) {
        if (email == null) {
            return false;
        }
        Long n = jdbc.sql("SELECT count(*) FROM member WHERE lower(email) = lower(?)").param(email)
                .query(Long.class).single();
        return n > 0;
    }

    public boolean existsByReferralCode(String code) {
        Long n = jdbc.sql("SELECT count(*) FROM member WHERE referral_code = ?").param(code)
                .query(Long.class).single();
        return n > 0;
    }

    public Optional<Member> findByReferralCode(String code) {
        return jdbc.sql("SELECT * FROM member WHERE referral_code = ?").param(code)
                .query(MemberRepository::map).optional();
    }

    public void insert(Member m) {
        jdbc.sql("""
                        INSERT INTO member
                          (id, external_id, first_name, last_name, nickname, email, phone, birth_date, gender, city,
                           status, channel, registered_at, referral_code, referred_by, referral_completed_at,
                           consents, attributes, labels, avatar_seed, profile_completed_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                cast(? AS jsonb), cast(? AS jsonb), ?::text[], ?, ?, ?)
                        """)
                .params(m.id(), m.externalId(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                        m.birthDate(), m.gender(), m.city(), m.status().name(), m.channel(),
                        m.registeredAt() == null ? null : java.sql.Timestamp.from(m.registeredAt()),
                        m.referralCode(), m.referredBy(),
                        m.referralCompletedAt() == null ? null : java.sql.Timestamp.from(m.referralCompletedAt()),
                        m.consentsJson(), m.attributesJson(), TextArrays.literal(m.labels()), m.avatarSeed(),
                        m.profileCompletedAt() == null ? null : java.sql.Timestamp.from(m.profileCompletedAt()),
                        m.version())
                .update();
    }

    /**
     * Aggiorna i campi anagrafici con lock ottimistico: applica solo se {@code version} combacia e la
     * incrementa. Ritorna {@code true} se ha aggiornato una riga.
     */
    public boolean updateFields(String id, long expectedVersion, String firstName, String lastName, String nickname,
                                String email, String phone, LocalDate birthDate, String gender, String city,
                                String consentsJson, String attributesJson, Instant profileCompletedAt) {
        int n = jdbc.sql("""
                        UPDATE member SET
                          first_name = ?, last_name = ?, nickname = ?, email = ?, phone = ?, birth_date = ?,
                          gender = ?, city = ?, consents = cast(? AS jsonb), attributes = cast(? AS jsonb),
                          profile_completed_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """)
                .params(firstName, lastName, nickname, email, phone, birthDate, gender, city,
                        consentsJson, attributesJson,
                        profileCompletedAt == null ? null : java.sql.Timestamp.from(profileCompletedAt),
                        id, expectedVersion)
                .update();
        return n == 1;
    }

    public void updateStatus(String id, MemberStatus status) {
        jdbc.sql("UPDATE member SET status = ?, version = version + 1 WHERE id = ?")
                .params(status.name(), id).update();
    }

    /**
     * Anonimizzazione (F-MBR-05): sovrascrive anagrafica, recapiti, consensi e attributi con i valori già anonimizzati
     * ({@link io.loyaltyhub.member.domain.Anonymization#apply}) e porta lo stato ad {@code ANONYMIZED}, con lock
     * ottimistico. {@code true} se ha aggiornato la riga.
     */
    public boolean anonymize(Member a, long expectedVersion) {
        int n = jdbc.sql("""
                        UPDATE member SET
                          external_id = ?, first_name = ?, last_name = ?, nickname = ?, email = ?, phone = ?,
                          birth_date = ?, gender = ?, city = ?, status = ?, consents = cast(? AS jsonb),
                          attributes = cast(? AS jsonb), avatar_seed = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """)
                .params(a.externalId(), a.firstName(), a.lastName(), a.nickname(), a.email(), a.phone(),
                        a.birthDate(), a.gender(), a.city(), a.status().name(), a.consentsJson(), a.attributesJson(),
                        a.avatarSeed(), a.id(), expectedVersion)
                .update();
        return n == 1;
    }

    /** Etichette aggiornate da un'azione (SPEC-GAP Q-80): incrementa la versione come ogni modifica anagrafica. */
    public void updateLabels(String id, List<String> labels) {
        jdbc.sql("UPDATE member SET labels = ?::text[], version = version + 1 WHERE id = ?")
                .params(TextArrays.literal(labels), id).update();
    }

    /** Soprannome e stato dei membri dati (Q-368): solo le due colonne che servono, nessun altro dato personale. */
    public record NicknameRow(String id, String nickname, MemberStatus status) {
    }

    /** Righe dei membri esistenti tra gli id dati, in un'unica query parametrica (regola 19). */
    public List<NicknameRow> nicknames(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, nickname, status FROM member WHERE id = ANY(?::text[])")
                .param(TextArrays.literal(ids))
                .query((rs, n) -> new NicknameRow(rs.getString("id"), rs.getString("nickname"),
                        MemberStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /** Id esistenti tra quelli dati (validazione dei segmenti statici). */
    public List<String> existingIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM member WHERE id = ANY(?::text[]) AND status <> 'ANONYMIZED'")
                .param(TextArrays.literal(ids)).query(String.class).list();
    }

    public List<Member> search(String q, MemberStatus status, String tier, int limit, int offset) {
        return search(q, status, tier, null, limit, offset);
    }

    public long count(String q, MemberStatus status, String tier) {
        return count(q, status, tier, null);
    }

    enum MemberColumn implements SqlColumn {
        ID("m.id"), EXTERNAL_ID("m.external_id"), EMAIL("m.email"),
        FIRST_NAME("m.first_name"), LAST_NAME("m.last_name"), NICKNAME("m.nickname"),
        STATUS("m.status"), TIER_CODE("p.tier_code");

        private final String sql;

        MemberColumn(String sql) {
            this.sql = sql;
        }

        @Override
        public String sql() {
            return sql;
        }
    }

    /** Elenco filtrato anche per segmento (codice o id, docs §3 "filtri … segment"). */
    public List<Member> search(String q, MemberStatus status, String tier, String segment, int limit, int offset) {
        SqlWhere where = buildWhere(q, status, tier);
        String sql = "SELECT m.* FROM member m LEFT JOIN member_projection p ON p.member_id = m.id" + where.sql() + segmentSql(segment, where);
        sql += " ORDER BY m.id LIMIT :limit OFFSET :offset";
        return bindSegment(where.bind(jdbc.sql(sql)), segment)
                .param("limit", limit)
                .param("offset", offset)
                .query(MemberRepository::map).list();
    }

    public long count(String q, MemberStatus status, String tier, String segment) {
        SqlWhere where = buildWhere(q, status, tier);
        String sql = "SELECT count(*) FROM member m LEFT JOIN member_projection p ON p.member_id = m.id" + where.sql() + segmentSql(segment, where);
        return bindSegment(where.bind(jdbc.sql(sql)), segment)
                .query(Long.class).single();
    }

    private String segmentSql(String segment, SqlWhere where) {
        if (segment != null && !segment.isBlank()) {
            String and = where.isEmpty() ? " WHERE " : " AND ";
            return and + "EXISTS (SELECT 1 FROM segment_member sm JOIN segment s ON s.id = sm.segment_id"
                    + " WHERE sm.member_id = m.id AND (s.code = :segment OR s.id = :segment))";
        }
        return "";
    }

    private JdbcClient.StatementSpec bindSegment(JdbcClient.StatementSpec spec, String segment) {
        if (segment != null && !segment.isBlank()) {
            return spec.param("segment", segment.trim());
        }
        return spec;
    }

    private SqlWhere buildWhere(String q, MemberStatus status, String tier) {
        return new SqlWhere()
                .when(q != null && !q.isBlank(), w -> w.anyOf(a -> a
                        .ilike(MemberColumn.ID, q.trim(), SqlWhere.Match.CONTAINS)
                        .ilike(MemberColumn.EXTERNAL_ID, q.trim(), SqlWhere.Match.CONTAINS)
                        .ilike(MemberColumn.EMAIL, q.trim(), SqlWhere.Match.CONTAINS)
                        .ilike(MemberColumn.FIRST_NAME, q.trim(), SqlWhere.Match.CONTAINS)
                        .ilike(MemberColumn.LAST_NAME, q.trim(), SqlWhere.Match.CONTAINS)
                        .ilike(MemberColumn.NICKNAME, q.trim(), SqlWhere.Match.CONTAINS)
                ))
                .when(status != null, w -> w.eq(MemberColumn.STATUS, status.name()))
                .when(tier != null && !tier.isBlank(), w -> w.eq(MemberColumn.TIER_CODE, tier.trim().toUpperCase()));
    }

    /** Membri in evidenza per il selettore demo (docs §3): quelli con una storia, esclusi gli anonimizzati. */
    public List<io.loyaltyhub.member.api.PersonaView> personas() {
        return jdbc.sql("""
                        SELECT m.id, m.first_name, m.last_name, m.nickname, m.avatar_seed,
                               m.attributes ->> 'story' AS story,
                               coalesce(p.tier_code, 'BASE') AS tier_code,
                               coalesce(p.balance_pts, 0) AS balance_pts
                        FROM member m LEFT JOIN member_projection p ON p.member_id = m.id
                        WHERE m.status <> 'ANONYMIZED' AND jsonb_exists(m.attributes, 'story')
                        ORDER BY m.id
                        """)
                .query((rs, n) -> new io.loyaltyhub.member.api.PersonaView(
                        rs.getString("id"),
                        displayName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("nickname"), rs.getString("id")),
                        rs.getString("tier_code"),
                        rs.getString("story"),
                        rs.getString("avatar_seed"),
                        rs.getLong("balance_pts")))
                .list();
    }

    private static String displayName(String first, String last, String nickname, String id) {
        if (first != null && last != null) {
            return first + " " + last;
        }
        return nickname != null ? nickname : id;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM member").update();
    }

    static Member map(ResultSet rs, int rowNum) throws SQLException {
        return new Member(
                rs.getString("id"),
                rs.getString("external_id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("nickname"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getObject("birth_date", LocalDate.class),
                rs.getString("gender"),
                rs.getString("city"),
                MemberStatus.valueOf(rs.getString("status")),
                rs.getString("channel"),
                instant(rs, "registered_at"),
                rs.getString("referral_code"),
                rs.getString("referred_by"),
                instant(rs, "referral_completed_at"),
                rs.getString("consents"),
                rs.getString("attributes"),
                TextArrays.toList(rs.getArray("labels")),
                rs.getString("avatar_seed"),
                instant(rs, "profile_completed_at"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
