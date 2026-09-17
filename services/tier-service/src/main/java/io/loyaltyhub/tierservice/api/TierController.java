package io.loyaltyhub.tierservice.api;

import io.loyaltyhub.tierservice.domain.TierOverride;
import io.loyaltyhub.tierservice.domain.TierPolicy;
import jakarta.validation.constraints.NotBlank;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Tier corrente del membro; lo stato è in tierservice.member_tier, aggiornato dal consumer dei movimenti STATUS. */
@RestController
@RequestMapping("/v1/tiers")
public class TierController {
    private final JdbcTemplate jdbc;
    private final TierPolicy policy = TierPolicy.example();
    private final io.loyaltyhub.tierservice.domain.TierSet tierSet;

    public TierController(JdbcTemplate jdbc, io.loyaltyhub.tierservice.domain.TierSet tierSet) { this.jdbc = jdbc; this.tierSet = tierSet; }

    /** Tier set attivo con condizioni, soglie, benefici e modalità di discesa (RF-105..RF-107). */
    @GetMapping("/sets/active")
    public io.loyaltyhub.tierservice.domain.TierSet activeSet() { return tierSet; }

    /** Benefici del tier corrente e progresso verso il successivo (RF-107). */
    @GetMapping("/members/{memberId}/progress")
    public Map<String, Object> progress(@PathVariable String memberId) {
        var cur = current(memberId);
        var t = tierSet.byCode((String) cur.get("tier"));
        var metrics = new io.loyaltyhub.tierservice.domain.TierSet.MemberMetrics(Map.of("status", ((Number) cur.get("statusPointsYear")).doubleValue()), null, null);
        return Map.of("tier", t.code(), "benefits", t.benefits(), "missingToNext", tierSet.missingToNext(t, metrics), "downgrade", tierSet.downgrade().mode().name());
    }

    /** Assegnazione da effetto di campagna (RF-81): come l'override manuale, con causale della campagna. */
    @PostMapping("/members/{memberId}/assign")
    public Map<String, Object> assign(@PathVariable String memberId, @RequestBody Map<String, String> body) {
        return override(memberId, new OverrideRequest(body.get("tierCode"), body.getOrDefault("reason", "campaign"), body.getOrDefault("actor", "rules-engine"), null));
    }

    @GetMapping("/members/{memberId}")
    public Map<String, Object> current(@PathVariable String memberId) {
        var rows = jdbc.queryForList("SELECT tier, status_points_year FROM tierservice.member_tier WHERE member_id = ?", memberId);
        if (rows.isEmpty()) return Map.of("memberId", memberId, "tier", "BASE", "statusPointsYear", 0);
        var r = rows.get(0);
        long pts = ((Number) r.get("status_points_year")).longValue();
        TierPolicy.Tier computed = policy.byCode((String) r.get("tier"));
        TierPolicy.Tier t = TierOverride.effective(policy, computed, activeOverride(memberId), Instant.now());
        TierPolicy.Tier next = policy.tiers().stream().filter(x -> x.order() == t.order() + 1).findFirst().orElse(null);
        return Map.of("memberId", memberId, "tier", t.code(), "statusPointsYear", pts,
                "nextTier", next == null ? "" : next.code(), "pointsToNext", next == null ? 0 : Math.max(0, next.statusThreshold() - pts),
                "manual", !t.equals(computed));
    }

    public record OverrideRequest(@NotBlank String tierCode, @NotBlank String reason, @NotBlank String actor, Instant until) {}

    /** Assegnazione manuale (RF-70): chiude l'eventuale override precedente e registra il nuovo con causale. */
    @PutMapping("/members/{memberId}/override")
    public Map<String, Object> override(@PathVariable String memberId, @RequestBody OverrideRequest o) {
        // `byCode` ripiega sul livello base: qui serve il rifiuto, altrimenti un codice sbagliato
        // diventa un override verso un tier inesistente e il membro risulta retrocesso (RF-70).
        policy.find(o.tierCode()).orElseThrow(() -> new UnknownTier(o.tierCode()));
        Instant now = Instant.now();
        jdbc.update("UPDATE tierservice.tier_override SET until_at = ? WHERE member_id = ? AND (until_at IS NULL OR until_at > ?)", Timestamp.from(now), memberId, Timestamp.from(now));
        jdbc.update("INSERT INTO tierservice.tier_override(member_id, tier, reason, actor, from_at, until_at) VALUES (?,?,?,?,?,?)",
                memberId, o.tierCode(), o.reason(), o.actor(), Timestamp.from(now), o.until() == null ? null : Timestamp.from(o.until()));
        jdbc.update("INSERT INTO tierservice.tier_history(member_id, from_tier, to_tier, reason) VALUES (?,?,?,'MANUAL')", memberId, null, o.tierCode());
        return current(memberId);
    }

    /** Codice tier non presente nel programma. */
    public static class UnknownTier extends RuntimeException {
        public UnknownTier(String code) { super("tier sconosciuto: " + code); }
    }

    @ExceptionHandler(UnknownTier.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> unknownTier(UnknownTier e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @DeleteMapping("/members/{memberId}/override")
    public Map<String, Object> clearOverride(@PathVariable String memberId) {
        jdbc.update("UPDATE tierservice.tier_override SET until_at = now() WHERE member_id = ? AND (until_at IS NULL OR until_at > now())", memberId);
        return current(memberId);
    }

    private TierOverride activeOverride(String memberId) {
        var rows = jdbc.queryForList("SELECT tier, reason, actor, from_at, until_at FROM tierservice.tier_override WHERE member_id = ? AND from_at <= now() AND (until_at IS NULL OR until_at > now()) ORDER BY from_at DESC LIMIT 1", memberId);
        if (rows.isEmpty()) return null;
        var r = rows.get(0);
        return new TierOverride(memberId, (String) r.get("tier"), (String) r.get("reason"), (String) r.get("actor"),
                ((Timestamp) r.get("from_at")).toInstant(), r.get("until_at") == null ? null : ((Timestamp) r.get("until_at")).toInstant());
    }
}
