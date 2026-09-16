package it.iren.loyalty.tierservice.api;

import it.iren.loyalty.tierservice.domain.TierPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Tier corrente del membro; lo stato è in tierservice.member_tier, aggiornato dal consumer dei movimenti STATUS. */
@RestController
@RequestMapping("/v1/tiers")
public class TierController {
    private final JdbcTemplate jdbc;
    private final TierPolicy policy = TierPolicy.example();

    public TierController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/members/{memberId}")
    public Map<String, Object> current(@PathVariable String memberId) {
        var rows = jdbc.queryForList("SELECT tier, status_points_year FROM tierservice.member_tier WHERE member_id = ?", memberId);
        if (rows.isEmpty()) return Map.of("memberId", memberId, "tier", "BASE", "statusPointsYear", 0);
        var r = rows.get(0);
        long pts = ((Number) r.get("status_points_year")).longValue();
        TierPolicy.Tier t = policy.byCode((String) r.get("tier"));
        TierPolicy.Tier next = policy.tiers().stream().filter(x -> x.order() == t.order() + 1).findFirst().orElse(null);
        return Map.of("memberId", memberId, "tier", t.code(), "statusPointsYear", pts,
                "nextTier", next == null ? "" : next.code(), "pointsToNext", next == null ? 0 : Math.max(0, next.statusThreshold() - pts));
    }
}
