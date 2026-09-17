package io.loyaltyhub.fraudservice.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.loyaltyhub.fraudservice.domain.RiskEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistenza degli eventi osservati e delle valutazioni; calcola le osservazioni aggregate per il motore. */
@Component
public class RiskStore {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final JdbcTemplate jdbc;

    public RiskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean observe(String eventId, String memberId, String kind, String actionType, long units, String deviceId, Double lat, Double lon, Instant at) {
        return jdbc.update("INSERT INTO fraudservice.observed_event(event_id, member_id, kind, action_type, units, device_id, lat, lon, occurred_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING",
                eventId, memberId, kind, actionType, units, deviceId, lat, lon, Timestamp.from(at)) > 0;
    }

    public RiskEngine.MemberActivity activity(String memberId, Instant now) {
        Timestamp h1 = Timestamp.from(now.minus(Duration.ofHours(1))), h24 = Timestamp.from(now.minus(Duration.ofHours(24))), d7 = Timestamp.from(now.minus(Duration.ofDays(7))), d30 = Timestamp.from(now.minus(Duration.ofDays(30)));
        int redemptions24h = count("SELECT count(*) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'REDEMPTION' AND occurred_at >= ?", memberId, h24);
        long earned24h = sum("SELECT coalesce(sum(units),0) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'EARNING' AND occurred_at >= ?", memberId, h24);
        long earned30d = sum("SELECT coalesce(sum(units),0) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'EARNING' AND occurred_at >= ? AND occurred_at < ?", memberId, d30, h24);
        Instant enrolled = jdbc.query("SELECT min(occurred_at) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'ENROLLED'", rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null, memberId);
        Long ageHours = enrolled == null ? null : Duration.between(enrolled, now).toHours();
        long first24h = enrolled == null ? 0 : sum("SELECT coalesce(sum(units),0) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'EARNING' AND occurred_at < ?", memberId, Timestamp.from(enrolled.plus(Duration.ofHours(24))));
        int failedCodes = count("SELECT count(*) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'CODE_FAILED' AND occurred_at >= ?", memberId, h1);
        int tx30 = count("SELECT count(*) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'ACTION' AND action_type = 'TRANSACTION' AND occurred_at >= ?", memberId, d30);
        int returns30 = count("SELECT count(*) FROM fraudservice.observed_event WHERE member_id = ? AND kind = 'RETURN' AND occurred_at >= ?", memberId, d30);
        int devices7d = Math.max(1, count("SELECT count(DISTINCT device_id) FROM fraudservice.observed_event WHERE member_id = ? AND device_id IS NOT NULL AND occurred_at >= ?", memberId, d7));
        int events1h = count("SELECT count(*) FROM fraudservice.observed_event WHERE member_id = ? AND kind IN ('ACTION','CHECK_IN','PLAY') AND occurred_at >= ?", memberId, h1);
        int accountsOnDevice = Math.max(1, jdbc.query("SELECT count(DISTINCT member_id) FROM fraudservice.observed_event WHERE device_id IN (SELECT DISTINCT device_id FROM fraudservice.observed_event WHERE member_id = ? AND device_id IS NOT NULL AND occurred_at >= ?) AND occurred_at >= ?",
                rs -> rs.next() ? rs.getInt(1) : 1, memberId, d30, d30));
        Double maxSpeed = jdbc.query("SELECT lat, lon, occurred_at FROM fraudservice.observed_event WHERE member_id = ? AND lat IS NOT NULL AND occurred_at >= ? ORDER BY occurred_at DESC LIMIT 10", rs -> {
            double max = 0; Double pl = null, po = null; Instant pt = null;
            while (rs.next()) {
                double lat = rs.getDouble(1), lon = rs.getDouble(2); Instant t = rs.getTimestamp(3).toInstant();
                if (pl != null) max = Math.max(max, RiskEngine.speedKmh(lat, lon, t, pl, po, pt));
                pl = lat; po = lon; pt = t;
            }
            return max;
        }, memberId, d7);
        return new RiskEngine.MemberActivity(memberId, redemptions24h, accountsOnDevice, earned24h, earned30d / 29.0, ageHours, first24h, maxSpeed, failedCodes, tx30, returns30, devices7d, events1h);
    }

    public Optional<Map<String, Object>> current(String memberId) {
        return jdbc.queryForList("SELECT member_id, score, level, reason_codes::text AS reasons, assessment::text AS assessment, blocked, policy_version, assessed_at FROM fraudservice.member_risk WHERE member_id = ?", memberId).stream().findFirst();
    }

    /** Salva la valutazione; ritorna il livello precedente (null se prima valutazione). */
    public String save(RiskEngine.Assessment a, boolean blocked, String triggerEvent) {
        String prev = jdbc.query("SELECT level FROM fraudservice.member_risk WHERE member_id = ? FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, a.memberId());
        try {
            String reasons = MAPPER.writeValueAsString(a.reasonCodes()), json = MAPPER.writeValueAsString(a);
            jdbc.update("INSERT INTO fraudservice.member_risk(member_id, score, level, reason_codes, assessment, blocked, policy_version, assessed_at) VALUES (?,?,?,?::jsonb,?::jsonb,?,?,?) " +
                            "ON CONFLICT (member_id) DO UPDATE SET score = EXCLUDED.score, level = EXCLUDED.level, reason_codes = EXCLUDED.reason_codes, assessment = EXCLUDED.assessment, blocked = EXCLUDED.blocked, policy_version = EXCLUDED.policy_version, assessed_at = EXCLUDED.assessed_at",
                    a.memberId(), a.score(), a.level(), reasons, json, blocked, a.policyVersion(), Timestamp.from(a.assessedAt()));
            if (prev == null || !prev.equals(a.level()))
                jdbc.update("INSERT INTO fraudservice.risk_history(member_id, from_level, to_level, score, reason_codes, trigger_event) VALUES (?,?,?,?,?::jsonb,?)", a.memberId(), prev, a.level(), a.score(), reasons, triggerEvent);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return prev;
    }

    public List<Map<String, Object>> history(String memberId) {
        return jdbc.queryForList("SELECT from_level, to_level, score, reason_codes::text AS reasons, trigger_event, changed_at FROM fraudservice.risk_history WHERE member_id = ? ORDER BY changed_at DESC LIMIT 100", memberId);
    }

    public List<String> membersToReassess(Instant olderThan) {
        return jdbc.queryForList("SELECT member_id FROM fraudservice.member_risk WHERE level <> 'LOW' AND assessed_at < ? LIMIT 500", String.class, Timestamp.from(olderThan));
    }

    public int purge(Instant before) { return jdbc.update("DELETE FROM fraudservice.observed_event WHERE occurred_at < ?", Timestamp.from(before)); }

    public List<Map<String, Object>> topRisk(int limit) {
        return jdbc.queryForList("SELECT member_id, score, level, reason_codes::text AS reasons, blocked, assessed_at FROM fraudservice.member_risk WHERE level <> 'LOW' ORDER BY score DESC, assessed_at DESC LIMIT ?", limit);
    }

    private int count(String sql, Object... args) { Integer v = jdbc.queryForObject(sql, Integer.class, args); return v == null ? 0 : v; }
    private long sum(String sql, Object... args) { Long v = jdbc.queryForObject(sql, Long.class, args); return v == null ? 0 : v; }
}
