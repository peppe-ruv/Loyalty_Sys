package it.iren.loyalty.contestservice.instantwin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Giocata instant win (RF-32..RF-34): una transazione per giocata, esito immediato.
 * Vince la prima giocata valida con timestamp server successivo a un istante vincente non ancora assegnato.
 * L'assegnazione usa SELECT ... FOR UPDATE SKIP LOCKED sull'istante, così due giocate concorrenti non prendono
 * lo stesso premio (criterio di accettazione: nessun premio assegnato due volte).
 */
@Service
public class InstantWinService {
    public record Outcome(UUID playId, boolean won, String prizeCode, Instant playedAt) {}
    public static class PlayLimitExceeded extends RuntimeException { public PlayLimitExceeded() { super("PLAY_LIMIT_EXCEEDED"); } }
    public static class ContestNotOpen extends RuntimeException { public ContestNotOpen() { super("CONTEST_NOT_OPEN"); } }

    private final JdbcTemplate jdbc;

    public InstantWinService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome play(UUID contestId, String memberId, String deviceFingerprint, String ip) {
        Instant now = Instant.now();
        var contest = jdbc.queryForMap("SELECT starts_at, ends_at, status, max_plays_per_member_per_day FROM contestservice.contest WHERE id = ?", contestId);
        Instant starts = ((java.sql.Timestamp) contest.get("starts_at")).toInstant();
        Instant ends = ((java.sql.Timestamp) contest.get("ends_at")).toInstant();
        if (!"RUNNING".equals(contest.get("status")) || now.isBefore(starts) || !now.isBefore(ends)) throw new ContestNotOpen();

        int maxPerDay = ((Number) contest.get("max_plays_per_member_per_day")).intValue();
        Integer today = jdbc.queryForObject("SELECT count(*) FROM contestservice.play WHERE contest_id = ? AND member_id = ? AND played_at::date = now()::date",
                Integer.class, contestId, memberId);
        if (today != null && today >= maxPerDay) throw new PlayLimitExceeded();

        // Istante vincente più vecchio, non assegnato, già trascorso: prima giocata valida vince.
        List<java.util.Map<String, Object>> won = jdbc.queryForList(
                "SELECT id, prize_code FROM contestservice.winning_instant WHERE contest_id = ? AND assigned_play_id IS NULL AND at <= ? " +
                "ORDER BY at LIMIT 1 FOR UPDATE SKIP LOCKED", contestId, java.sql.Timestamp.from(now));

        UUID playId = UUID.randomUUID();
        String prev = jdbc.query("SELECT hash FROM contestservice.play WHERE contest_id = ? ORDER BY seq DESC LIMIT 1 FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, contestId);
        String outcome = won.isEmpty() ? "LOST" : "WON";
        String matched = won.isEmpty() ? null : won.get(0).get("id").toString();
        String hash = PlayLedger.hash(prev, playId.toString(), memberId, now.toEpochMilli(), outcome, matched);

        jdbc.update("INSERT INTO contestservice.play(id, contest_id, member_id, played_at, outcome, matched_instant_id, device_fingerprint, ip, prev_hash, hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                playId, contestId, memberId, java.sql.Timestamp.from(now), outcome, matched == null ? null : UUID.fromString(matched), deviceFingerprint, ip, prev, hash);
        if (!won.isEmpty()) {
            jdbc.update("UPDATE contestservice.winning_instant SET assigned_play_id = ?, assigned_at = ? WHERE id = ?",
                    playId, java.sql.Timestamp.from(now), won.get(0).get("id"));
        }
        return new Outcome(playId, !won.isEmpty(), won.isEmpty() ? null : (String) won.get(0).get("prize_code"), now);
    }
}
