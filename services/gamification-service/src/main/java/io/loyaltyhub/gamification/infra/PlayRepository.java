package io.loyaltyhub.gamification.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Giocate (docs/servizi/gamification-service.md §2): vincitori, statistiche, consegna dei premi fisici. */
@Repository
public class PlayRepository {

    public record Winner(String playId, String memberId, String nickname, String prizeCode, String prizeName, String prizeType,
                         Instant playedAt, String deliveryStatus, String deliveryNote) {
    }

    public record DayStat(LocalDate day, long plays, long wins) {
    }

    public record Totals(long plays, long wins, long players, long winners) {
        public static final Totals NONE = new Totals(0, 0, 0, 0);
    }

    private final JdbcClient jdbc;

    public PlayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Winner> winners(String contestId) {
        return jdbc.sql("""
                        SELECT pl.id, pl.member_id, s.nickname, pr.code AS prize_code, pr.name AS prize_name,
                          pr.type AS prize_type, pl.played_at, pl.delivery_status, pl.delivery_note
                        FROM play pl JOIN prize pr ON pr.id = pl.prize_id
                        LEFT JOIN gamification_member_snapshot s ON s.member_id = pl.member_id
                        WHERE pl.contest_id = ? AND pl.outcome = 'WIN'
                        ORDER BY pl.played_at DESC, pl.id
                        """)
                .param(contestId)
                .query((rs, n) -> new Winner(rs.getString("id"), rs.getString("member_id"), rs.getString("nickname"),
                        rs.getString("prize_code"), rs.getString("prize_name"), rs.getString("prize_type"),
                        ContestRepository.inst(rs, "played_at"), rs.getString("delivery_status"),
                        rs.getString("delivery_note")))
                .list();
    }

    /** Giocate e vincite per concorso (elenco BO-14). */
    public java.util.Map<String, Totals> totalsByContest() {
        java.util.Map<String, Totals> out = new java.util.HashMap<>();
        jdbc.sql("""
                        SELECT contest_id, count(*) AS plays, count(*) FILTER (WHERE outcome = 'WIN') AS wins,
                          count(DISTINCT member_id) AS players,
                          count(DISTINCT member_id) FILTER (WHERE outcome = 'WIN') AS winners
                        FROM play GROUP BY contest_id
                        """)
                .query((rs, n) -> out.put(rs.getString("contest_id"), new Totals(rs.getLong("plays"), rs.getLong("wins"),
                        rs.getLong("players"), rs.getLong("winners"))))
                .list();
        return out;
    }

    public Totals totals(String contestId) {
        return jdbc.sql("""
                        SELECT count(*) AS plays, count(*) FILTER (WHERE outcome = 'WIN') AS wins,
                          count(DISTINCT member_id) AS players,
                          count(DISTINCT member_id) FILTER (WHERE outcome = 'WIN') AS winners
                        FROM play WHERE contest_id = ?
                        """)
                .param(contestId)
                .query((rs, n) -> new Totals(rs.getLong("plays"), rs.getLong("wins"), rs.getLong("players"), rs.getLong("winners")))
                .single();
    }

    public List<DayStat> daily(String contestId) {
        return jdbc.sql("""
                        SELECT play_date, count(*) AS plays, count(*) FILTER (WHERE outcome = 'WIN') AS wins
                        FROM play WHERE contest_id = ? GROUP BY play_date ORDER BY play_date
                        """)
                .param(contestId)
                .query((rs, n) -> new DayStat(rs.getObject("play_date", LocalDate.class), rs.getLong("plays"), rs.getLong("wins")))
                .list();
    }

    public record PlayRef(String id, String contestId, String outcome, String prizeType, String deliveryStatus) {
    }

    public Optional<PlayRef> lock(String playId) {
        return jdbc.sql("""
                        SELECT pl.id, pl.contest_id, pl.outcome, pr.type AS prize_type, pl.delivery_status
                        FROM play pl LEFT JOIN prize pr ON pr.id = pl.prize_id WHERE pl.id = ? FOR UPDATE OF pl
                        """)
                .param(playId)
                .query((rs, n) -> new PlayRef(rs.getString("id"), rs.getString("contest_id"), rs.getString("outcome"),
                        rs.getString("prize_type"), rs.getString("delivery_status")))
                .optional();
    }

    // ---------- giocate e crediti (docs/03 §6) ----------

    public record NewPlay(String id, String contestId, String memberId, String kind, String outcome, String prizeId,
                          Instant playedAt, LocalDate playDate, String correlationId, String deliveryStatus) {
    }

    public record MemberPlay(String playId, Instant playedAt, String outcome, String kind, String prizeCode, String prizeName,
                             String prizeType, String deliveryStatus) {
    }

    public void insert(NewPlay p) {
        jdbc.sql("""
                        INSERT INTO play (id, contest_id, member_id, kind, outcome, prize_id, played_at, play_date,
                          correlation_id, delivery_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(p.id(), p.contestId(), p.memberId(), p.kind(), p.outcome(), p.prizeId(),
                        java.sql.Timestamp.from(p.playedAt()), p.playDate(), p.correlationId(), p.deliveryStatus())
                .update();
    }

    /** Storico in blocco (seed): una sola istruzione per centinaia di giocate. */
    public void insertAll(List<NewPlay> plays) {
        if (plays.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO play (id, contest_id, member_id, kind, outcome, prize_id, played_at, play_date,
                          correlation_id, delivery_status)
                        SELECT i, c, m, k, o, NULLIF(p, ''), t, d, NULL, s
                        FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::timestamptz[],
                          ?::date[], ?::text[]) AS x(i, c, m, k, o, p, t, d, s)
                        """)
                .params(TextArrays.literal(plays.stream().map(NewPlay::id).toList()),
                        TextArrays.literal(plays.stream().map(NewPlay::contestId).toList()),
                        TextArrays.literal(plays.stream().map(NewPlay::memberId).toList()),
                        TextArrays.literal(plays.stream().map(NewPlay::kind).toList()),
                        TextArrays.literal(plays.stream().map(NewPlay::outcome).toList()),
                        TextArrays.literal(plays.stream().map(p -> p.prizeId() == null ? "" : p.prizeId()).toList()),
                        TextArrays.literal(plays.stream().map(p -> p.playedAt().toString()).toList()),
                        TextArrays.literal(plays.stream().map(p -> p.playDate().toString()).toList()),
                        TextArrays.literal(plays.stream().map(NewPlay::deliveryStatus).toList()))
                .update();
    }

    public int countOnDate(String memberId, String contestId, LocalDate day) {
        return jdbc.sql("SELECT count(*) FROM play WHERE member_id = ? AND contest_id = ? AND play_date = ?")
                .params(memberId, contestId, day).query(Integer.class).single();
    }

    public boolean freeUsedOn(String memberId, String contestId, LocalDate day) {
        return jdbc.sql("""
                        SELECT count(*) FROM play WHERE member_id = ? AND contest_id = ? AND play_date = ? AND kind = 'FREE_DAILY'
                        """)
                .params(memberId, contestId, day).query(Integer.class).single() > 0;
    }

    public int countCreditPlays(String memberId, String contestId) {
        return jdbc.sql("SELECT count(*) FROM play WHERE member_id = ? AND contest_id = ? AND kind = 'CREDIT'")
                .params(memberId, contestId).query(Integer.class).single();
    }

    public int countWins(String memberId, String contestId) {
        return jdbc.sql("SELECT count(*) FROM play WHERE member_id = ? AND contest_id = ? AND outcome = 'WIN'")
                .params(memberId, contestId).query(Integer.class).single();
    }

    public List<MemberPlay> history(String memberId, String contestId, int limit) {
        return jdbc.sql("""
                        SELECT pl.id, pl.played_at, pl.outcome, pl.kind, pr.code AS prize_code, pr.name AS prize_name,
                          pr.type AS prize_type, pl.delivery_status
                        FROM play pl LEFT JOIN prize pr ON pr.id = pl.prize_id
                        WHERE pl.member_id = ? AND pl.contest_id = ?
                        ORDER BY pl.played_at DESC, pl.id DESC LIMIT ?
                        """)
                .params(memberId, contestId, limit)
                .query((rs, n) -> new MemberPlay(rs.getString("id"), ContestRepository.inst(rs, "played_at"),
                        rs.getString("outcome"), rs.getString("kind"), rs.getString("prize_code"), rs.getString("prize_name"),
                        rs.getString("prize_type"), rs.getString("delivery_status")))
                .list();
    }

    // ---------- crediti di gioco (play_grant) ----------

    /** Idempotente su {@code effectId}: {@code true} se il credito è nuovo. */
    public boolean insertGrant(String id, String memberId, String contestId, int count, String effectId, String campaignCode,
                               Instant grantedAt) {
        return jdbc.sql("""
                        INSERT INTO play_grant (id, member_id, contest_id, count, effect_id, campaign_code, granted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (effect_id) DO NOTHING
                        """)
                .params(id, memberId, contestId, count, effectId, campaignCode, java.sql.Timestamp.from(grantedAt))
                .update() == 1;
    }

    public int sumGrants(String memberId, String contestId) {
        return jdbc.sql("SELECT COALESCE(sum(count), 0) FROM play_grant WHERE member_id = ? AND contest_id = ?")
                .params(memberId, contestId).query(Integer.class).single();
    }

    /** Lock di transazione per (membro, concorso): le giocate dello stesso membro si serializzano (docs/servizi §5). */
    public void lockMemberContest(String memberId, String contestId) {
        long key = ((long) memberId.hashCode() << 32) ^ (contestId.hashCode() & 0xffffffffL);
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(key).query((rs, n) -> 1).list();
    }

    public void updateDelivery(String playId, String status, String note) {
        jdbc.sql("UPDATE play SET delivery_status = ?, delivery_note = ? WHERE id = ?").params(status, note, playId).update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM play").update();
        jdbc.sql("DELETE FROM play_grant").update();
    }
}
