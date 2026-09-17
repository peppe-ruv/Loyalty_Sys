package io.loyaltyhub.engagementservice.api;

import io.loyaltyhub.engagementservice.app.EngagementService;
import io.loyaltyhub.engagementservice.domain.*;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** API gamification (RF-90..RF-97): stato del membro su achievement, challenge, badge, classifiche; assegnazione badge; aggiornamento manuale del progresso. */
@RestController
@RequestMapping("/v1")
public class EngagementController {
    public record GrantBadge(@NotBlank String memberId, @NotBlank String grantKey) {}
    public record ManualProgress(@NotBlank String memberId, @NotBlank String actionType, Map<String, Object> attributes, @NotBlank String reason) {}

    private final JdbcTemplate jdbc;
    private final EngagementService engagement;
    private final Definitions defs;
    public EngagementController(JdbcTemplate jdbc, EngagementService engagement, Definitions defs) { this.jdbc = jdbc; this.engagement = engagement; this.defs = defs; }

    @GetMapping("/badges") public List<Badge> badges() { return defs.badges(); }
    @GetMapping("/badges/members/{memberId}")
    public List<String> memberBadges(@PathVariable String memberId) { return jdbc.queryForList("SELECT badge_code FROM engagementservice.member_badge WHERE member_id = ? ORDER BY first_granted_at", String.class, memberId); }
    @GetMapping("/badges/members/{memberId}/details")
    public List<Map<String, Object>> memberBadgeDetails(@PathVariable String memberId) { return jdbc.queryForList("SELECT badge_code, completed_count, first_granted_at, last_granted_at, source FROM engagementservice.member_badge WHERE member_id = ?", memberId); }
    @PostMapping("/badges/{code}/grants")
    public Badge.Grant grant(@PathVariable String code, @RequestBody GrantBadge g) { return engagement.grantBadge(g.memberId(), code, g.grantKey()); }

    @GetMapping("/achievements") public List<Achievement> achievements() { return defs.achievements(); }
    @GetMapping("/achievements/members/{memberId}")
    public List<Map<String, Object>> achievementStatus(@PathVariable String memberId) { return jdbc.queryForList("SELECT definition_id, completed_count, state, updated_at FROM engagementservice.achievement_progress WHERE member_id = ?", memberId); }

    @GetMapping("/challenges") public List<Challenge> challenges() { return defs.challenges(); }
    @GetMapping("/challenges/members/{memberId}")
    public List<Map<String, Object>> challengeStatus(@PathVariable String memberId) { return jdbc.queryForList("SELECT definition_id, completed_count, state, updated_at FROM engagementservice.challenge_state WHERE member_id = ?", memberId); }

    /** Aggiornamento manuale del progresso: passa da un'azione interna con causale, così resta tracciato. */
    @PostMapping("/progress")
    public Map<String, String> manualProgress(@RequestBody ManualProgress p) {
        var attrs = new java.util.HashMap<String, Object>(p.attributes() == null ? Map.of() : p.attributes());
        attrs.put("manualReason", p.reason());
        engagement.apply(p.memberId(), new io.loyaltyhub.common.event.RewardingAction(p.actionType(), "manual:" + p.memberId() + ":" + p.actionType() + ":" + System.currentTimeMillis(), null, java.time.Instant.now(), null, attrs));
        return Map.of("status", "APPLIED");
    }

    @GetMapping("/leaderboards") public List<Leaderboard> leaderboards() { return defs.leaderboards(); }
    @GetMapping("/leaderboards/{id}")
    public List<Map<String, Object>> ranking(@PathVariable String id, @RequestParam(required = false) String group, @RequestParam(defaultValue = "100") int limit) {
        return jdbc.queryForList("SELECT rank, member_id, group_value, value FROM engagementservice.leaderboard_rank WHERE leaderboard_id = ? AND (? IS NULL OR group_value = ?) ORDER BY group_value, rank LIMIT ?", id, group, group, limit);
    }
    @GetMapping("/leaderboards/{id}/members/{memberId}")
    public Map<String, Object> myRank(@PathVariable String id, @PathVariable String memberId) {
        var rows = jdbc.queryForList("SELECT rank, group_value, value FROM engagementservice.leaderboard_rank WHERE leaderboard_id = ? AND member_id = ?", id, memberId);
        var score = jdbc.queryForList("SELECT value FROM engagementservice.leaderboard_score WHERE leaderboard_id = ? AND member_id = ?", id, memberId);
        return Map.of("leaderboardId", id, "memberId", memberId, "ranked", rows, "currentValue", score.isEmpty() ? 0 : score.get(0).get("value"));
    }
}
