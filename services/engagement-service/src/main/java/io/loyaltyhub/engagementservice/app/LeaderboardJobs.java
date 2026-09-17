package io.loyaltyhub.engagementservice.app;

import io.loyaltyhub.engagementservice.domain.AchievementEngine;
import io.loyaltyhub.engagementservice.domain.Definitions;
import io.loyaltyhub.engagementservice.domain.Leaderboard;
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

    /**
     * Chiusura dei cicli premianti (RF-94). La riga del ciclo è una prenotazione: finché la
     * premiazione non è confermata (`rewarded_at`), il giro successivo la riprende. Serve perché i
     * premi si assegnano con chiamate a catalogo, ledger ed engagement, e un errore a metà elenco
     * lasciava i vincitori successivi senza nulla, con il ciclo già segnato come chiuso.
     * Il secondo tentativo è innocuo: ogni premio ha la sua chiave di idempotenza.
     */
    @Scheduled(cron = "${leaderboards.cycle-cron:0 30 0 * * *}", zone = "Europe/Rome")
    public void closeCycles() {
        Instant now = Instant.now();
        for (Leaderboard lb : defs.leaderboards()) {
            if (!lb.active() || lb.rewardingCycle() == null) continue;
            String cycleKey = AchievementEngine.periodKey(now.minusSeconds(3600), lb.rewardingCycle().period());
            if (!claimCycle(lb.id(), cycleKey, now) && !pendingReward(lb.id(), cycleKey)) continue;
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
            // Solo ora il ciclo è davvero chiuso: da qui i punteggi si possono azzerare.
            jdbc.update("UPDATE engagementservice.leaderboard_cycle SET rewarded_at = ? WHERE leaderboard_id = ? AND cycle_key = ?",
                    java.sql.Timestamp.from(Instant.now()), lb.id(), cycleKey);
            jdbc.update("DELETE FROM engagementservice.leaderboard_score WHERE leaderboard_id = ?", lb.id());
        }
    }

    /** Prenota il ciclo; false se qualcuno l'aveva già prenotato (anche un'esecuzione precedente). */
    private boolean claimCycle(String leaderboardId, String cycleKey, Instant now) {
        return jdbc.update("INSERT INTO engagementservice.leaderboard_cycle(leaderboard_id, cycle_key, closed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                leaderboardId, cycleKey, java.sql.Timestamp.from(now)) == 1;
    }

    /** Ciclo prenotato ma con la premiazione mai confermata: va ripreso. */
    private boolean pendingReward(String leaderboardId, String cycleKey) {
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM engagementservice.leaderboard_cycle WHERE leaderboard_id = ? AND cycle_key = ? AND rewarded_at IS NULL",
                Integer.class, leaderboardId, cycleKey);
        return pending != null && pending > 0;
    }
}
