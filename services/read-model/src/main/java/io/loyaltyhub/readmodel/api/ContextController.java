package io.loyaltyhub.readmodel.api;

import io.loyaltyhub.readmodel.app.ContextStore;
import io.loyaltyhub.readmodel.domain.CustomerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API di contesto (RF-126): il Customer 360 in una chiamata per decision-service, sito e backoffice; snapshot per i
 * segmenti; pubblico delle automazioni; analytics di base (RF-111). Paginazione keyset ({@code after}, {@code size}).
 */
@RestController
public class ContextController {
    private final ContextStore store;
    private final JdbcTemplate jdbc;
    public ContextController(ContextStore store, JdbcTemplate jdbc) { this.store = store; this.jdbc = jdbc; }

    @GetMapping("/v1/context/{memberId}")
    public CustomerContext context(@PathVariable String memberId) { return store.get(memberId); }

    @GetMapping("/v1/read/members/{memberId}/summary")
    public Map<String, Object> summary(@PathVariable String memberId) {
        var c = store.get(memberId);
        var premio = c.loyalty().wallets().get("PREMIO");
        return Map.of("memberId", memberId, "tier", c.loyalty().tier(), "statusPointsYear", c.loyalty().statusPointsYear(), "rewardAvailable", premio == null ? 0 : premio.active(), "updatedAt", c.updatedAt());
    }

    /** Snapshot per segment-service (RF-71): stessa forma di MemberSnapshot. */
    @GetMapping("/v1/read/members/{memberId}/snapshot")
    public Map<String, Object> snapshot(@PathVariable String memberId) { return toSnapshot(store.get(memberId)); }

    @GetMapping("/v1/read/members/snapshots")
    public List<Map<String, Object>> snapshots(@RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "1000") int size) {
        return store.page(after, Math.min(size, 5000)).stream().map(ContextController::toSnapshot).toList();
    }

    @GetMapping("/v1/read/members/{memberId}/redemptions")
    public List<CustomerContext.Offer> redemptions(@PathVariable String memberId) { return store.get(memberId).engagement().recentOffers().stream().filter(o -> "ISSUE_REWARD".equals(o.action())).toList(); }

    @GetMapping("/v1/read/members/{memberId}/plays")
    public List<CustomerContext.ActionSummary> plays(@PathVariable String memberId) { return store.get(memberId).behaviour().recentActions().stream().filter(a -> a.actionType().startsWith("CONTEST_") || "WHEEL_SPUN".equals(a.actionType())).toList(); }

    /** Pubblico delle automazioni (RF-86): membri attivi filtrati per segmenti/tier e, per BIRTHDAY/ANNIVERSARY, per giorno. */
    @PostMapping("/v1/read/audiences")
    public List<String> audience(@RequestBody Map<String, Object> req) {
        String schedule = String.valueOf(req.getOrDefault("schedule", "DAILY"));
        String sql = "SELECT member_id FROM readmodel.customer_context WHERE context->'identity'->>'status' = 'ACTIVE'";
        if ("ANNIVERSARY".equals(schedule)) sql += " AND to_char((context->'identity'->>'enrolledAt')::timestamptz AT TIME ZONE 'Europe/Rome', 'MM-DD') = to_char(now() AT TIME ZONE 'Europe/Rome', 'MM-DD')";
        if ("BIRTHDAY".equals(schedule)) sql += " AND to_char((context->'identity'->'customFields'->>'dataCompleanno')::date, 'MM-DD') = to_char(now() AT TIME ZONE 'Europe/Rome', 'MM-DD')";
        return jdbc.queryForList(sql + " ORDER BY member_id LIMIT 200000", String.class);
    }

    /** Analytics di base (RF-111): registrati, attivi, transazioni e media nel periodo; il dettaglio è in ClickHouse/Superset. */
    @GetMapping("/v1/read/analytics/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        Long registered = jdbc.queryForObject("SELECT count(*) FROM readmodel.customer_context WHERE (context->'identity'->>'enrolledAt')::timestamptz >= now() - (? || ' days')::interval", Long.class, String.valueOf(days));
        Long active = jdbc.queryForObject("SELECT count(*) FROM readmodel.customer_context WHERE updated_at >= now() - (? || ' days')::interval", Long.class, String.valueOf(days));
        Map<String, Object> tiers = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT context->'loyalty'->>'tier' AS tier, count(*) FROM readmodel.customer_context GROUP BY 1", rs -> { tiers.put(rs.getString(1), rs.getLong(2)); });
        return Map.of("days", days, "registeredMembers", registered, "activeMembers", active, "membersByTier", tiers);
    }

    static Map<String, Object> toSnapshot(CustomerContext c) {
        var premio = c.loyalty().wallets().get("PREMIO");
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("memberId", c.memberId()); m.put("enrolledAt", c.identity().enrolledAt()); m.put("tier", c.loyalty().tier()); m.put("labels", c.identity().labels()); m.put("consents", c.consents());
        m.put("premioAvailable", premio == null ? 0 : premio.active());
        m.put("actions", c.behaviour().recentActions().stream().map(a -> Map.of("actionType", a.actionType(), "occurredAt", a.occurredAt(), "amountEur", a.amountEur() == null ? null : a.amountEur(), "channel", a.channel(), "lines", List.of())).toList());
        m.put("customFields", c.identity().customFields()); m.put("badges", c.engagement().badges()); m.put("achievementsCompleted", c.engagement().achievementsCompleted());
        m.put("achievementProgress", Map.of()); m.put("challengesCompleted", c.engagement().challengesCompleted());
        m.put("campaignCompletions", c.engagement().campaignsCompleted30d().stream().map(id -> Map.of("campaignId", id, "at", c.updatedAt())).toList());
        return m;
    }
}
