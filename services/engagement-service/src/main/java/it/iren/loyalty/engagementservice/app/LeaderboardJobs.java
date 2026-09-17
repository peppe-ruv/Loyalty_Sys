package it.iren.loyalty.engagementservice.app;

import it.iren.loyalty.engagementservice.domain.AchievementEngine;
import it.iren.loyalty.engagementservice.domain.Definitions;
import it.iren.loyalty.engagementservice.domain.Leaderboard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Ricalcolo delle classifiche ogni 4 ore (RF-93) e chiusura dei cicli premianti (RF-94): a fine ciclo i premi per
 * posizione vengono assegnati tramite catalog-redemption (premio), ledger (unità) o badge; il ciclo chiuso è archiviato.
 */
@Component
public class LeaderboardJobs {
    private final JdbcTemplate jdbc;
    private final Definitions defs;
    private final EngagementService engagement;
    private final RestClient catalog;
    private final RestClient ledger;

    public LeaderboardJobs(JdbcTemplate jdbc, Definitions defs, EngagementService engagement, RestClient.Builder builder) {
        this.jdbc = jdbc; this.defs = defs; this.engagement = engagement;
        this.catalog = builder.baseUrl(System.getenv().getOrDefault("CATALOG_URL", "http://catalog-redemption:8085")).build();
        this.ledger = builder.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
    }

    @Scheduled(cron = "${leaderboards.rank-cron:0 0 */4 * * *}")
    public void rank() {
        for (Leaderboard lb : defs.leaderboards()) {
            if (!lb.active()) continue;
            List<Leaderboard.Score> scores = jdbc.query("SELECT s.member_id, coalesce(g.group_value, ''), s.value FROM engagementservice.leaderboard_score s LEFT JOIN engagementservice.member_group g ON g.member_id = s.member_id AND g.group_key = ? WHERE s.leaderboard_id = ?",
                    (rs, i) -> new Leaderboard.Score(rs.getString(1), rs.getString(2), rs.getDouble(3)), lb.groupBy() == null ? "" : lb.groupBy(), lb.id());
            var ranked = Leaderboard.rank(scores, lb.topN());
            jdbc.update("DELETE FROM engagementservice.leaderboard_rank WHERE leaderboard_id = ?", lb.id());
            ranked.forEach((g, entries) -> entries.forEach(e -> jdbc.update("INSERT INTO engagementservice.leaderboard_rank(leaderboard_id, group_value, rank, member_id, value, computed_at) VALUES (?,?,?,?,?,?)",
                    lb.id(), g, e.rank(), e.memberId(), e.value(), java.sql.Timestamp.from(Instant.now()))));
        }
    }

    @Scheduled(cron = "${leaderboards.cycle-cron:0 30 0 * * *}", zone = "Europe/Rome")
    public void closeCycles() {
        Instant now = Instant.now();
        for (Leaderboard lb : defs.leaderboards()) {
            if (!lb.active() || lb.rewardingCycle() == null) continue;
            String cycleKey = AchievementEngine.periodKey(now.minusSeconds(3600), lb.rewardingCycle().period());
            if (jdbc.update("INSERT INTO engagementservice.leaderboard_cycle(leaderboard_id, cycle_key, closed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING", lb.id(), cycleKey, java.sql.Timestamp.from(now)) == 0) continue;
            rank();
            var entries = jdbc.query("SELECT rank, member_id, group_value, value FROM engagementservice.leaderboard_rank WHERE leaderboard_id = ?",
                    (rs, i) -> new Leaderboard.Entry(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getDouble(4)), lb.id());
            for (var pair : lb.rewardsFor(entries)) {
                var e = pair.getKey(); var r = pair.getValue();
                String key = "leaderboard:" + lb.id() + ":" + cycleKey + ":" + e.memberId();
                if (r.rewardId() != null) catalog.post().uri("/v1/grants").body(Map.of("memberId", e.memberId(), "rewardId", r.rewardId(), "grantKey", key)).retrieve().toBodilessEntity();
                if (r.units() > 0) ledger.post().uri("/v1/ledger/postings").body(Map.of("memberId", e.memberId(), "actionKey", key, "currency", r.wallet() == null ? "PREMIO" : r.wallet(), "amount", r.units(), "reason", "LEADERBOARD:" + lb.id())).retrieve().toBodilessEntity();
                if (r.badgeCode() != null) engagement.grantBadge(e.memberId(), r.badgeCode(), key);
            }
            jdbc.update("DELETE FROM engagementservice.leaderboard_score WHERE leaderboard_id = ?", lb.id());
        }
    }
}
