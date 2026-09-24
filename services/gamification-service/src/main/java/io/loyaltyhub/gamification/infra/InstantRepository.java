package io.loyaltyhub.gamification.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Istanti vincenti (docs/03 §6; F-IW-03). */
@Repository
public class InstantRepository {

    public record InstantRow(String id, String prizeId, String prizeCode, String prizeName, Instant instantAt,
                             String status, String claimedBy, Instant claimedAt, String playId, boolean planted) {
    }

    public record Counts(long total, long open, long claimed, long voided) {
        public static final Counts NONE = new Counts(0, 0, 0, 0);
    }

    public record DayCount(LocalDate day, long total, long open, long claimed, long voided) {
    }

    private final JdbcClient jdbc;

    public InstantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteByContest(String contestId) {
        jdbc.sql("DELETE FROM winning_instant WHERE contest_id = ?").param(contestId).update();
    }

    /** Inserimento in blocco (centinaia di istanti in un comando: niente round-trip per istante). */
    public void insertAll(String contestId, List<String> ids, List<String> prizeIds, List<Instant> at) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO winning_instant (id, contest_id, prize_id, instant_at, status)
                        SELECT i, ?, p, t, 'OPEN'
                        FROM unnest(?::text[], ?::text[], ?::timestamptz[]) AS x(i, p, t)
                        """)
                .params(contestId, TextArrays.literal(ids), TextArrays.literal(prizeIds),
                        TextArrays.literal(at.stream().map(Instant::toString).toList()))
                .update();
    }

    public long count(String contestId, String status, String prizeId) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM winning_instant w WHERE w.contest_id = ?");
        List<Object> params = filters(sql, contestId, status, prizeId);
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<InstantRow> search(String contestId, String status, String prizeId, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT w.id, w.prize_id, p.code AS prize_code, p.name AS prize_name, w.instant_at, w.status, w.claimed_by,
                  w.claimed_at, w.play_id, w.planted
                FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                WHERE w.contest_id = ?""");
        List<Object> params = filters(sql, contestId, status, prizeId);
        sql.append(" ORDER BY w.instant_at, w.id LIMIT ? OFFSET ?");
        params.add(size);
        params.add(page * size);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new InstantRow(rs.getString("id"), rs.getString("prize_id"), rs.getString("prize_code"),
                        rs.getString("prize_name"), ContestRepository.inst(rs, "instant_at"), rs.getString("status"),
                        rs.getString("claimed_by"), ContestRepository.inst(rs, "claimed_at"), rs.getString("play_id"),
                        rs.getBoolean("planted")))
                .list();
    }

    /** Istanti per giorno ({@code Europe/Rome}) e stato: l'istogramma che vede anche chi non può vedere gli istanti. */
    public List<DayCount> histogram(String contestId) {
        return jdbc.sql("""
                        SELECT (instant_at AT TIME ZONE 'Europe/Rome')::date AS day, count(*) AS total,
                          count(*) FILTER (WHERE status = 'OPEN') AS open,
                          count(*) FILTER (WHERE status = 'CLAIMED') AS claimed,
                          count(*) FILTER (WHERE status = 'VOID') AS voided
                        FROM winning_instant WHERE contest_id = ? GROUP BY 1 ORDER BY 1
                        """)
                .param(contestId)
                .query((rs, n) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("total"),
                        rs.getLong("open"), rs.getLong("claimed"), rs.getLong("voided")))
                .list();
    }

    /** Conteggi per stato, per concorso (elenco BO-14 e dettaglio). */
    public java.util.Map<String, Counts> countsByContest() {
        java.util.Map<String, Counts> out = new java.util.HashMap<>();
        jdbc.sql("""
                        SELECT contest_id, count(*) AS total, count(*) FILTER (WHERE status = 'OPEN') AS open,
                          count(*) FILTER (WHERE status = 'CLAIMED') AS claimed,
                          count(*) FILTER (WHERE status = 'VOID') AS voided
                        FROM winning_instant GROUP BY contest_id
                        """)
                .query((rs, n) -> out.put(rs.getString("contest_id"), new Counts(rs.getLong("total"), rs.getLong("open"),
                        rs.getLong("claimed"), rs.getLong("voided"))))
                .list();
        return out;
    }

    /**
     * Claim atomico (docs/03 §6): il primo istante {@code OPEN} già passato, saltando quelli bloccati da giocate
     * concorrenti. Con {@code prizeId} valorizzato restituisce il premio vinto; vuoto → giocata perdente.
     */
    public java.util.Optional<String> claim(String contestId, String memberId, String playId, Instant now) {
        return jdbc.sql("""
                        UPDATE winning_instant SET status = 'CLAIMED', claimed_by = ?, claimed_at = ?, play_id = ?
                        WHERE id = (SELECT id FROM winning_instant
                                    WHERE contest_id = ? AND status = 'OPEN' AND instant_at <= ?
                                    ORDER BY instant_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING prize_id
                        """)
                .params(memberId, ts(now), playId, contestId, ts(now))
                .query(String.class)
                .optional();
    }

    public record HistoryClaim(String instantId, String memberId, String playId, Instant claimedAt) {
    }

    /** Istanti aperti già passati, in ordine: servono al seed per ricostruire lo storico coerente. */
    public List<InstantRow> openBefore(String contestId, Instant before) {
        return jdbc.sql("""
                        SELECT w.id, w.prize_id, p.code AS prize_code, p.name AS prize_name, w.instant_at, w.status,
                          w.claimed_by, w.claimed_at, w.play_id, w.planted
                        FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                        WHERE w.contest_id = ? AND w.status = 'OPEN' AND w.instant_at <= ?
                        ORDER BY w.instant_at, w.id
                        """)
                .params(contestId, ts(before))
                .query((rs, n) -> new InstantRow(rs.getString("id"), rs.getString("prize_id"), rs.getString("prize_code"),
                        rs.getString("prize_name"), ContestRepository.inst(rs, "instant_at"), rs.getString("status"),
                        rs.getString("claimed_by"), ContestRepository.inst(rs, "claimed_at"), rs.getString("play_id"),
                        rs.getBoolean("planted")))
                .list();
    }

    public void claimAll(List<HistoryClaim> claims) {
        if (claims.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE winning_instant w SET status = 'CLAIMED', claimed_by = x.m, claimed_at = x.t, play_id = x.p
                        FROM unnest(?::text[], ?::text[], ?::text[], ?::timestamptz[]) AS x(i, m, p, t)
                        WHERE w.id = x.i
                        """)
                .params(TextArrays.literal(claims.stream().map(HistoryClaim::instantId).toList()),
                        TextArrays.literal(claims.stream().map(HistoryClaim::memberId).toList()),
                        TextArrays.literal(claims.stream().map(HistoryClaim::playId).toList()),
                        TextArrays.literal(claims.stream().map(c -> c.claimedAt().toString()).toList()))
                .update();
    }

    /** Istanti ancora aperti → {@code VOID} (fine concorso); restituisce quanti. */
    public int voidOpen(String contestId) {
        return jdbc.sql("UPDATE winning_instant SET status = 'VOID' WHERE contest_id = ? AND status = 'OPEN'")
                .param(contestId).update();
    }

    private static List<Object> filters(StringBuilder sql, String contestId, String status, String prizeId) {
        List<Object> params = new ArrayList<>();
        params.add(contestId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND w.status = ?");
            params.add(status.toUpperCase());
        }
        if (prizeId != null && !prizeId.isBlank()) {
            sql.append(" AND w.prize_id = ?");
            params.add(prizeId);
        }
        return params;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
