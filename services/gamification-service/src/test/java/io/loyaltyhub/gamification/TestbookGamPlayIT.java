package io.loyaltyhub.gamification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — giocata instant win (docs/testbook/TB-GAM-gioco.md §2–§4): tabella decisionale della giocata
 * (stato × periodo × membro × vincite), crediti (gratuita × crediti × tetto giornaliero), giorno di Roma, risoluzione
 * degli istanti ({@code PLY}, {@code CRD}, {@code CLM}, {@code PTL}). Oracolo: docs/03 §2, §6, gamification §3, §5.
 */
class TestbookGamPlayIT extends TestbookGamBase {

    // TESTBOOK: ambiguo, vedi TB-GAM-PLY-053, TB-GAM-PLY-054, TB-GAM-PLY-055 (ordine dei controlli) e
    // TB-GAM-CRD-004, TB-GAM-CRD-028 (tetto raggiunto e nessuna giocata): si asserisce il comportamento attuale.
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/giocata.csv", numLinesToSkip = 1)
    void giocata(String id, String desc, String status, String timing, String memberStatus, String free, int credits,
                 String cap, int today, String winsCap, int priorWins, String matured, int expHttp, String expCode,
                 String expKind, String expOutcome, String expAvailBefore, String expAvailAfter) {
        scenario(id, status, timing, memberStatus, free, credits, cap, today, winsCap, priorWins, matured, expHttp, expCode,
                expKind, expOutcome, expAvailBefore, expAvailAfter);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/crediti.csv", numLinesToSkip = 1)
    void crediti(String id, String desc, String status, String timing, String memberStatus, String free, int credits,
                 String cap, int today, String winsCap, int priorWins, String matured, int expHttp, String expCode,
                 String expKind, String expOutcome, String expAvailBefore, String expAvailAfter) {
        scenario(id, status, timing, memberStatus, free, credits, cap, today, winsCap, priorWins, matured, expHttp, expCode,
                expKind, expOutcome, expAvailBefore, expAvailAfter);
    }

    /** Due giocate a {@code t1} e {@code t2}: la prima riesce, la seconda dipende dal giorno di Roma. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/crediti-sequenza.csv", numLinesToSkip = 1)
    void sequenza(String id, String desc, String t1, String t2, String free, int credits, String cap, String expKind1,
                  int expHttp2, String expCode2, String expKind2) {
        Instant first = Instant.parse(t1);
        Instant second = Instant.parse(t2);
        CLOCK.set(first);
        Map<String, Object> o = new HashMap<>();
        o.put("freePlayDaily", "Y".equals(free));
        if (opt(cap) != null) o.put("maxPlaysPerMemberPerDay", Integer.parseInt(cap));
        String code = contestCode(id);
        String contest = contestIn(code, "LIVE", first.minus(Duration.ofDays(1)), second.plus(Duration.ofDays(1)),
                second.plus(Duration.ofHours(1)), o);
        String m = member("ACTIVE");
        grant(m, contest, credits);

        Resp r1 = play(code, m);
        assertThat(r1.status()).as(r1.text()).isEqualTo(200);
        assertThat(kind(contest, m, r1)).isEqualTo(expKind1);

        CLOCK.set(second);
        Resp r2 = play(code, m);
        assertThat(r2.status()).as(r2.text()).isEqualTo(expHttp2);
        if (expHttp2 == 200) {
            assertThat(kind(contest, m, r2)).isEqualTo(expKind2);
        } else {
            assertThat(r2.code()).isEqualTo(expCode2);
            assertThat(plays(contest, m)).isEqualTo(1);
        }
    }

    /** Un solo istante in posizione {@code offsetMs} rispetto ad adesso, nello stato indicato. */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/claim.csv", numLinesToSkip = 1)
    void claim(String id, String desc, long offsetMs, String instantStatus, String expOutcome) {
        Instant now = T0;
        CLOCK.set(now);
        String code = contestCode(id);
        String contest = contestIn(code, "LIVE", now.minus(Duration.ofDays(40)), now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofHours(5)), null);
        String instant = matureOne(contest, null, plus(now, offsetMs));
        if (!"OPEN".equals(instantStatus)) {
            jdbc.sql("UPDATE winning_instant SET status = ?, claimed_by = CASE WHEN CAST(? AS text) = 'CLAIMED' THEN 'MBR-000001' END WHERE id = ?")
                    .params(instantStatus, instantStatus, instant).update();
        }
        String m = member("ACTIVE");
        Resp r = play(code, m);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        assertThat(r.body().path("outcome").asString()).isEqualTo(expOutcome);
        assertThat(instantStatus(instant)).isEqualTo("WIN".equals(expOutcome) ? "CLAIMED" : instantStatus);
        assertThat(remaining(contest, "PTS-10")).isEqualTo("WIN".equals(expOutcome) ? 4 : 5);
    }

    @Test
    @DisplayName("[TB-GAM-CLM-007] due istanti maturi di premi diversi: vince il più vecchio")
    void oldestFirst() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-007");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), Map.of("prizes", List.of(prize("PTS-A", "POINTS", 5L, null, 2),
                        prize("PTS-B", "POINTS", 7L, null, 2))));
        String older = matureOne(contest, "PTS-B", T0.minusSeconds(2));
        String newer = matureOne(contest, "PTS-A", T0.minusSeconds(1));
        Resp r = play(code, member("ACTIVE"));
        assertThat(r.body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(r.body().path("prize").path("code").asString()).isEqualTo("PTS-B");
        assertThat(instantStatus(older)).isEqualTo("CLAIMED");
        assertThat(instantStatus(newer)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("[TB-GAM-CLM-008] un istante maturo e due giocatori in sequenza: una sola vincita")
    void oneInstantOneWin() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-008");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        matureOne(contest, null, T0.minusSeconds(1));
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("LOSE");
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'CLAIMED'", contest)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-CLM-009] istante maturo di un altro concorso: qui si perde")
    void otherContestInstant() {
        CLOCK.set(T0);
        String codeA = contestCode("TB-GAM-CLM-009");
        String codeB = contestCode("TB-GAM-CLM-009");
        contestIn(codeA, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        String b = contestIn(codeB, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        String instantB = matureOne(b, null, T0.minusSeconds(1));
        assertThat(play(codeA, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("LOSE");
        assertThat(instantStatus(instantB)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("[TB-GAM-CLM-010] vincita: istante CLAIMED con membro, giocata e ora; premio residuo −1")
    void winMarksInstant() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-010");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        String instant = matureOne(contest, null, T0.minusSeconds(1));
        String m = member("ACTIVE");
        Resp r = play(code, m);
        Map<String, Object> row = jdbc.sql("SELECT claimed_by, play_id, claimed_at FROM winning_instant WHERE id = ?")
                .param(instant).query().singleRow();
        assertThat(row.get("claimed_by")).isEqualTo(m);
        assertThat(row.get("play_id")).isEqualTo(r.body().path("playId").asString());
        assertThat(((java.sql.Timestamp) row.get("claimed_at")).toInstant()).isEqualTo(T0);
        assertThat(remaining(contest, "PTS-10")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT outcome FROM play WHERE id = ?").param(r.body().path("playId").asString())
                .query(String.class).single()).isEqualTo("WIN");
    }

    @Test
    @DisplayName("[TB-GAM-CLM-011] perdita: nessun istante toccato e premio residuo invariato")
    void loseTouchesNothing() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-011");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("LOSE");
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'OPEN'", contest)).isEqualTo(5);
        assertThat(remaining(contest, "PTS-10")).isEqualTo(5);
    }

    @Test
    @DisplayName("[TB-GAM-CLM-012] perdita: fatto contest.played LOSE e nessun contest.won")
    void loseFacts() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-012");
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        String m = member("ACTIVE");
        Resp r = play(code, m);
        List<JsonNode> played = facts("contest.played", m);
        assertThat(played).hasSize(1);
        JsonNode d = played.get(0).path("data");
        assertThat(d.path("contestCode").asString()).isEqualTo(code);
        assertThat(d.path("playId").asString()).isEqualTo(r.body().path("playId").asString());
        assertThat(d.path("outcome").asString()).isEqualTo("LOSE");
        assertThat(d.path("kind").asString()).isEqualTo("FREE_DAILY");
        assertThat(facts("contest.won", m)).isEmpty();
    }

    @Test
    @DisplayName("[TB-GAM-CLM-013] vincita: contest.played e contest.won nello stesso tracciato della risposta")
    void winFacts() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-013");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        matureOne(contest, null, T0.minusSeconds(1));
        String m = member("ACTIVE");
        Resp r = play(code, m);
        String correlation = r.body().path("correlationId").asString();
        List<JsonNode> played = facts("contest.played", m);
        List<JsonNode> won = facts("contest.won", m);
        assertThat(played).hasSize(1);
        assertThat(won).hasSize(1);
        assertThat(played.get(0).path("lhcorrelationid").asString()).isEqualTo(correlation);
        assertThat(won.get(0).path("lhcorrelationid").asString()).isEqualTo(correlation);
        assertThat(won.get(0).path("data").path("prizeCode").asString()).isEqualTo("PTS-10");
    }

    @Test
    @DisplayName("[TB-GAM-CLM-014] lo stesso membro vince due volte senza maxWinsPerMember")
    void winTwice() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-CLM-014");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        matureOne(contest, null, T0.minusSeconds(2));
        matureOne(contest, null, T0.minusSeconds(1));
        String m = member("ACTIVE");
        grant(m, contest, 1);
        assertThat(play(code, m).body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(play(code, m).body().path("outcome").asString()).isEqualTo("WIN");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLY-056 (memberId assente: codice d'errore non specificato)
    @Test
    @DisplayName("[TB-GAM-PLY-056] memberId assente nel corpo")
    void memberMissing() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLY-056");
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        Resp r = play(code, null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("MEMBER_REQUIRED");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLY-057
    @Test
    @DisplayName("[TB-GAM-PLY-057] memberId di soli spazi")
    void memberBlank() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLY-057");
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        Resp r = play(code, "   ");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("MEMBER_REQUIRED");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-058] codice concorso inesistente")
    void unknownContest() {
        assertThat(play("IW-NON-ESISTE", member("ACTIVE")).status()).isEqualTo(404);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLY-059 (docs/06 §2 «id o code nei path» contro il path {code} del portale)
    @Test
    @DisplayName("[TB-GAM-PLY-059] id del concorso al posto del codice nel path del portale")
    void idInsteadOfCode() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLY-059");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        assertThat(play(contest, member("ACTIVE")).status()).isEqualTo(404);
    }

    // ---------- portale: elenco dei concorsi (PT-05) ----------

    @Test
    @DisplayName("[TB-GAM-PTL-001] concorso LIVE in periodo: in elenco senza quantità né istanti")
    void portalListsLive() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-001");
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)), T0.plus(Duration.ofHours(5)), null);
        JsonNode c = portalContest(member("ACTIVE"), code);
        assertThat(c).isNotNull();
        assertThat(c.path("mechanic").asString()).isEqualTo("WHEEL");
        assertThat(c.path("endAt").asString()).isNotBlank();
        assertThat(c.path("playsAvailable").asInt()).isEqualTo(1);
        assertThat(c.path("freePlayAvailable").asBoolean()).isTrue();
        JsonNode p = c.path("prizes").get(0);
        assertThat(p.path("code").asString()).isEqualTo("PTS-10");
        assertThat(p.path("type").asString()).isEqualTo("POINTS");
        String text = c.toString();
        assertThat(text).doesNotContain("quantity").doesNotContain("instant").doesNotContain("remaining");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PTL-002 (LIVE con startAt futuro: la spec elenca «concorsi LIVE»)
    @Test
    @DisplayName("[TB-GAM-PTL-002] concorso LIVE non ancora iniziato: non in elenco")
    void portalHidesFuture() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-002");
        contestIn(code, "LIVE", T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)), T0.plus(Duration.ofDays(2)), null);
        assertThat(portalContest(member("ACTIVE"), code)).isNull();
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PTL-003 (LIVE con endAt passato prima del job di fine concorso)
    @Test
    @DisplayName("[TB-GAM-PTL-003] concorso LIVE con endAt passato: non in elenco")
    void portalHidesExpired() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-003");
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(10)), T0.minus(Duration.ofDays(1)), T0.minus(Duration.ofDays(2)), null);
        assertThat(portalContest(member("ACTIVE"), code)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-PTL-004] concorso PAUSED: non in elenco")
    void portalHidesPaused() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-004");
        contestIn(code, "PAUSED", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)), T0.plus(Duration.ofHours(5)), null);
        assertThat(portalContest(member("ACTIVE"), code)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-PTL-005] concorso APPROVED non pubblicato: non in elenco")
    void portalHidesApproved() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-005");
        contestIn(code, "APPROVED", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)), T0.plus(Duration.ofHours(5)), null);
        assertThat(portalContest(member("ACTIVE"), code)).isNull();
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PTL-006 (membro non attivo nel portale: nessuna fonte)
    @Test
    @DisplayName("[TB-GAM-PTL-006] membro BLOCKED: concorso in elenco con 0 giocate disponibili")
    void portalBlockedMember() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-006");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)),
                T0.plus(Duration.ofHours(5)), null);
        String m = member("BLOCKED");
        grant(m, contest, 2);
        JsonNode c = portalContest(m, code);
        assertThat(c).isNotNull();
        assertThat(c.path("playsAvailable").asInt()).isZero();
        assertThat(c.path("freePlayAvailable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("[TB-GAM-PTL-007] storico delle giocate del membro: più recente prima, con esito e tipo")
    void portalHistory() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PTL-007");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(12)),
                T0.plus(Duration.ofHours(5)), null);
        String m = member("ACTIVE");
        grant(m, contest, 2);
        String first = play(code, m).body().path("playId").asString();
        CLOCK.set(T0.plusSeconds(60));
        play(code, m);
        CLOCK.set(T0.plusSeconds(120));
        String last = play(code, m).body().path("playId").asString();
        play(code, member("ACTIVE"));
        JsonNode history = ok("GET", "/v1/portal/contests/" + code + "/plays?memberId=" + m, null, null, 200);
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(0).path("playId").asString()).isEqualTo(last);
        assertThat(history.get(2).path("playId").asString()).isEqualTo(first);
        assertThat(history.get(2).path("kind").asString()).isEqualTo("FREE_DAILY");
        assertThat(history.get(0).path("kind").asString()).isEqualTo("CREDIT");
        assertThat(history.get(0).path("outcome").asString()).isEqualTo("LOSE");
    }

    // ---------- motore dello scenario ----------

    private void scenario(String id, String status, String timing, String memberStatus, String free, int credits, String cap,
                          int today, String winsCap, int priorWins, String matured, int expHttp, String expCode,
                          String expKind, String expOutcome, String expAvailBefore, String expAvailAfter) {
        Instant now = T0;
        Instant start;
        Instant end;
        switch (timing) {
            case "BEFORE" -> { start = plus(now, 1); end = now.plus(Duration.ofHours(2)); }
            case "AT_START" -> { start = now; end = now.plus(Duration.ofHours(2)); }
            case "IN" -> { start = now.minus(Duration.ofHours(1)); end = now.plus(Duration.ofHours(1)); }
            case "END_M1" -> { start = now.minus(Duration.ofHours(2)); end = plus(now, 1); }
            case "AT_END" -> { start = now.minus(Duration.ofHours(2)); end = now; }
            case "AFTER" -> { start = now.minus(Duration.ofDays(3)); end = now.minus(Duration.ofDays(1)); }
            default -> throw new IllegalArgumentException(timing);
        }
        CLOCK.set(now);
        Map<String, Object> o = new HashMap<>();
        o.put("freePlayDaily", !"NO".equals(free));
        if (opt(cap) != null) o.put("maxPlaysPerMemberPerDay", Integer.parseInt(cap));
        if (opt(winsCap) != null) o.put("maxWinsPerMember", Integer.parseInt(winsCap));
        String code = contestCode(id);
        String contest = contestIn(code, status, start, end, now.plus(Duration.ofDays(1)), o);
        String instant = "Y".equals(matured) ? matureOne(contest, null, now.minusSeconds(1)) : null;
        String m = member(memberStatus);

        // Giocate di oggi: la gratuita (se usata) più giocate da credito; vincite passate nei giorni precedenti.
        String prize = prizeId(contest, "PTS-10");
        boolean freeUsed = "USED".equals(free);
        if (freeUsed) priorPlay(contest, m, "FREE_DAILY", "LOSE", null, now.minusSeconds(60));
        int creditPlaysToday = today - (freeUsed ? 1 : 0);
        for (int i = 0; i < creditPlaysToday; i++) priorPlay(contest, m, "CREDIT", "LOSE", null, now.minusSeconds(120 + i));
        for (int i = 1; i <= priorWins; i++) priorPlay(contest, m, "FREE_DAILY", "WIN", prize, now.minus(Duration.ofDays(i)));
        grant(m, contest, credits + creditPlaysToday);
        long before = plays(contest, m);

        if (opt(expAvailBefore) != null) {
            JsonNode c = portalContest(m, code);
            assertThat(c).as("concorso in elenco nel portale").isNotNull();
            assertThat(c.path("playsAvailable").asInt()).as("playsAvailable").isEqualTo(Integer.parseInt(expAvailBefore));
            assertThat(c.path("freePlayAvailable").asBoolean()).as("freePlayAvailable").isEqualTo("AVAIL".equals(free));
            assertThat(c.path("credits").asInt()).as("credits").isEqualTo(credits);
        }

        Resp r = play(code, m);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 200) {
            assertThat(r.body().path("outcome").asString()).isEqualTo(expOutcome);
            assertThat(kind(contest, m, r)).isEqualTo(expKind);
            assertThat(r.body().path("playsAvailable").asInt()).isEqualTo(Integer.parseInt(expAvailAfter));
            assertThat(plays(contest, m)).isEqualTo(before + 1);
            if (instant != null) {
                assertThat(instantStatus(instant)).isEqualTo("WIN".equals(expOutcome) ? "CLAIMED" : "OPEN");
            }
        } else {
            assertThat(r.code()).isEqualTo(expCode);
            assertThat(plays(contest, m)).as("nessuna giocata registrata").isEqualTo(before);
            if (instant != null) assertThat(instantStatus(instant)).isEqualTo("OPEN");
        }
    }

    private String kind(String contest, String memberId, Resp r) {
        return jdbc.sql("SELECT kind FROM play WHERE id = ?").param(r.body().path("playId").asString()).query(String.class).single();
    }
}
