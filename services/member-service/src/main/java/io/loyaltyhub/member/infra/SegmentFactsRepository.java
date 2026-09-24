package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.SegmentFacts;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carica i {@link SegmentFacts} dei membri per valutare i criteri (docs/03 §10): anagrafica + proiezione saldi/tier +
 * statistiche + finestre mobili dai contatori giornalieri, rispetto alla data di riferimento {@code asOf}. Gli
 * anonimizzati sono esclusi: non appartengono a nessun segmento (docs/03 §2).
 * SPEC-GAP: Q-85 — nessuna fonte dice su quali stati si valutano i criteri: popolazione = tutti tranne gli anonimizzati
 * (così un criterio su {@code member.status} ha senso); i seed che in docs/10 elencano solo membri attivi mettono
 * esplicitamente {@code member.status eq ACTIVE}.
 */
@Repository
public class SegmentFactsRepository {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SegmentFactsRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<SegmentFacts> loadAll(Instant asOf) {
        LocalDate day = LocalDate.ofInstant(asOf, ROME);
        Map<String, Map<String, long[]>> windows = new HashMap<>(); // member → type → [count30d]
        Map<String, Double> amounts = new HashMap<>();
        jdbc.sql("""
                        SELECT member_id, action_type,
                               coalesce(sum(count) FILTER (WHERE day > ?), 0) AS c30,
                               coalesce(sum(purchase_amount) FILTER (WHERE day > ?), 0) AS a90
                        FROM member_activity_day WHERE day <= ?
                        GROUP BY member_id, action_type
                        """)
                .params(day.minusDays(30), day.minusDays(90), day)
                .query((rs, n) -> {
                    String m = rs.getString("member_id");
                    windows.computeIfAbsent(m, k -> new HashMap<>()).put(rs.getString("action_type"), new long[]{rs.getLong("c30")});
                    amounts.merge(m, rs.getBigDecimal("a90").doubleValue(), Double::sum);
                    return null;
                })
                .list();

        List<SegmentFacts> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT m.id, m.first_name, m.last_name, m.nickname, m.status, m.labels, m.attributes::text AS attributes,
                               m.registered_at, m.birth_date, m.city,
                               coalesce(p.tier_code, 'BASE') AS tier_code, coalesce(p.balance_pts, 0) AS balance_pts,
                               coalesce(p.lifetime_earned_pts, 0) AS lifetime_earned_pts,
                               s.last_activity_at, coalesce(s.actions_by_type, '{}'::jsonb)::text AS actions_by_type
                        FROM member m
                        LEFT JOIN member_projection p ON p.member_id = m.id
                        LEFT JOIN member_stats s ON s.member_id = m.id
                        WHERE m.status <> 'ANONYMIZED'
                        ORDER BY m.id
                        """)
                .query((rs, n) -> {
                    String id = rs.getString("id");
                    Map<String, SegmentFacts.ActionWindow> actions = new HashMap<>();
                    JsonNode byType = mapper.readTree(rs.getString("actions_by_type"));
                    Map<String, long[]> w = windows.getOrDefault(id, Map.of());
                    for (Map.Entry<String, JsonNode> e : byType.properties()) {
                        JsonNode v = e.getValue();
                        long total = v.isNumber() ? v.asLong() : v.path("total").asLong(0);
                        long[] c = w.get(e.getKey());
                        actions.put(e.getKey(), new SegmentFacts.ActionWindow(c == null ? 0 : c[0], total));
                    }
                    for (Map.Entry<String, long[]> e : w.entrySet()) {
                        actions.putIfAbsent(e.getKey(), new SegmentFacts.ActionWindow(e.getValue()[0], e.getValue()[0]));
                    }
                    Timestamp reg = rs.getTimestamp("registered_at");
                    Timestamp last = rs.getTimestamp("last_activity_at");
                    return new SegmentFacts(id,
                            displayName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("nickname"), id),
                            rs.getString("status"), rs.getString("tier_code"), TextArrays.toList(rs.getArray("labels")),
                            mapper.readTree(rs.getString("attributes")),
                            reg == null ? null : reg.toInstant(), rs.getObject("birth_date", LocalDate.class),
                            rs.getString("city"), rs.getLong("balance_pts"), rs.getLong("lifetime_earned_pts"),
                            last == null ? null : last.toInstant(), actions, amounts.getOrDefault(id, 0.0));
                })
                .list()
                .forEach(out::add);
        return out;
    }

    static String displayName(String first, String last, String nickname, String id) {
        if (first != null && last != null) {
            return first + " " + last;
        }
        return nickname != null ? nickname : id;
    }
}
