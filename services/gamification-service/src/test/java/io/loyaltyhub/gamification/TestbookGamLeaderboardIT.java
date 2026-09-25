package io.loyaltyhub.gamification;

import io.loyaltyhub.gamification.application.LeaderboardService;
import io.loyaltyhub.gamification.messaging.FactsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — classifiche ({@code LDB}: punteggi, periodi, ranking, parimerito, solo membri ACTIVE, portale;
 * {@code LCF}: configurazione) (docs/testbook/TB-GAM-gioco.md §16–§17). I fatti {@code wallet.points.earned} e le
 * azioni entrano dal servizio applicativo come li consegnerebbe il listener; un caso entra dal listener stesso. Oracolo: docs/03 §8,
 * gamification §2, §3, §5, F-LDB-01, docs/09 PT-10, docs/08 §2 e BO-16, Q-59, Q-60.
 */
class TestbookGamLeaderboardIT extends TestbookGamBase {

    private static final Instant T = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired
    private LeaderboardService leaderboardService;
    @Autowired
    private FactsListener factsListener;

    // ---------- punteggi ----------

    @Test
    @DisplayName("[TB-GAM-LDB-001] wallet.points.earned PTS 100: +100 nella classifica PTS_EARNED")
    void ptsEarned() {
        String code = board("TB-GAM-LDB-001", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", 100, T);
        assertThat(score(code, "2026-09", m)).isEqualTo(100);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-002] wallet.points.earned STS 50: +50 nella classifica STS_EARNED, niente in quella PTS")
    void stsEarned() {
        String sts = board("TB-GAM-LDB-002", "STS_EARNED", "MONTH", 10, null);
        String pts = board("TB-GAM-LDB-002", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "STS", 50, T);
        assertThat(score(sts, "2026-09", m)).isEqualTo(50);
        assertThat(score(pts, "2026-09", m)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-003] importo 0: nessun punteggio")
    void zeroAmount() {
        String code = board("TB-GAM-LDB-003", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", 0, T);
        assertThat(score(code, "2026-09", m)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-004] importo negativo: nessun punteggio")
    void negativeAmount() {
        String code = board("TB-GAM-LDB-004", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", -10, T);
        assertThat(score(code, "2026-09", m)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-005] due accrediti 100 e 60 nello stesso mese: 160")
    void sumOfAmounts() {
        String code = board("TB-GAM-LDB-005", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", 100, T);
        earned(m, "PTS", 60, T.plusSeconds(60));
        assertThat(score(code, "2026-09", m)).isEqualTo(160);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-006] ACTION_COUNT: azione elencata +1")
    void actionListed() {
        String code = board("TB-GAM-LDB-006", "ACTION_COUNT", "MONTH", 10, List.of("tb.ldb006.a"));
        String m = member("ACTIVE");
        action(m, "tb.ldb006.a", T);
        assertThat(score(code, "2026-09", m)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-007] ACTION_COUNT: azione non elencata non conta")
    void actionNotListed() {
        String code = board("TB-GAM-LDB-007", "ACTION_COUNT", "MONTH", 10, List.of("tb.ldb007.a"));
        String m = member("ACTIVE");
        action(m, "tb.ldb007.b", T);
        assertThat(score(code, "2026-09", m)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-008] MONTH: 31 agosto 23:59:59 e 1 settembre 00:00 di Roma in due periodi")
    void monthBoundary() {
        String code = board("TB-GAM-LDB-008", "PTS_EARNED", "MONTH", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", 10, Instant.parse("2026-08-31T21:59:59Z"));
        earned(m, "PTS", 20, Instant.parse("2026-08-31T22:00:00Z"));
        assertThat(score(code, "2026-08", m)).isEqualTo(10);
        assertThat(score(code, "2026-09", m)).isEqualTo(20);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-009] EDITION: 31 dicembre 23:59:59 e 1 gennaio 00:00 di Roma in ED-2026 e ED-2027 (Q-59)")
    void editionBoundary() {
        String code = board("TB-GAM-LDB-009", "STS_EARNED", "EDITION", 10, null);
        String m = member("ACTIVE");
        earned(m, "STS", 10, Instant.parse("2026-12-31T22:59:59Z"));
        earned(m, "STS", 20, Instant.parse("2026-12-31T23:00:00Z"));
        assertThat(score(code, "ED-2026", m)).isEqualTo(10);
        assertThat(score(code, "ED-2027", m)).isEqualTo(20);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-010] ALL_TIME: anni diversi nello stesso periodo")
    void allTime() {
        String code = board("TB-GAM-LDB-010", "PTS_EARNED", "ALL_TIME", 10, null);
        String m = member("ACTIVE");
        earned(m, "PTS", 10, Instant.parse("2025-01-15T10:00:00Z"));
        earned(m, "PTS", 20, Instant.parse("2027-06-15T10:00:00Z"));
        assertThat(score(code, "ALL", m)).isEqualTo(30);
    }

    // ---------- ranking ----------

    @Test
    @DisplayName("[TB-GAM-LDB-011] ordine per punteggio decrescente")
    void orderByScore() {
        String code = board("TB-GAM-LDB-011", "PTS_EARNED", "MONTH", 10, null);
        String a = member("ACTIVE");
        String b = member("ACTIVE");
        String c = member("ACTIVE");
        earned(a, "PTS", 50, T);
        earned(b, "PTS", 150, T.plusSeconds(1));
        earned(c, "PTS", 100, T.plusSeconds(2));
        assertThat(rankingMembers(code)).containsExactly(b, c, a);
        assertThat(ranking(code).get(0).path("rank").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-012] parimerito: prima chi ha raggiunto il punteggio prima")
    void tieEarlierFirst() {
        String code = board("TB-GAM-LDB-012", "PTS_EARNED", "MONTH", 10, null);
        String a = member("ACTIVE");
        String b = member("ACTIVE");
        earned(b, "PTS", 100, T);
        earned(a, "PTS", 100, T.plusSeconds(5));
        assertThat(rankingMembers(code)).containsExactly(b, a);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-013] parimerito raggiunto con due accrediti: conta l'ultimo, non il primo")
    void tieLastIncrement() {
        String code = board("TB-GAM-LDB-013", "PTS_EARNED", "MONTH", 10, null);
        String a = member("ACTIVE");
        String b = member("ACTIVE");
        earned(a, "PTS", 50, T);
        earned(b, "PTS", 100, T.plusSeconds(10));
        earned(a, "PTS", 50, T.plusSeconds(20));
        assertThat(rankingMembers(code)).containsExactly(b, a);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-014] membro BLOCKED con punteggio alto: escluso dal ranking")
    void blockedExcluded() {
        excluded("TB-GAM-LDB-014", "BLOCKED");
    }

    @Test
    @DisplayName("[TB-GAM-LDB-015] membro INACTIVE: escluso dal ranking")
    void inactiveExcluded() {
        excluded("TB-GAM-LDB-015", "INACTIVE");
    }

    @Test
    @DisplayName("[TB-GAM-LDB-016] membro ANONYMIZED: escluso dal ranking")
    void anonymizedExcluded() {
        excluded("TB-GAM-LDB-016", "ANONYMIZED");
    }

    @Test
    @DisplayName("[TB-GAM-LDB-017] membro senza snapshot: escluso dal ranking")
    void unknownExcluded() {
        excluded("TB-GAM-LDB-017", "UNKNOWN");
    }

    @Test
    @DisplayName("[TB-GAM-LDB-018] membro tornato ACTIVE: rientra col punteggio conservato")
    void backToActive() {
        String code = board("TB-GAM-LDB-018", "PTS_EARNED", "MONTH", 10, null);
        String m = member("BLOCKED");
        earned(m, "PTS", 300, T);
        assertThat(rankingMembers(code)).doesNotContain(m);
        jdbc.sql("UPDATE gamification_member_snapshot SET status = 'ACTIVE' WHERE member_id = ?").param(m).update();
        assertThat(rankingMembers(code)).containsExactly(m);
        assertThat(ranking(code).get(0).path("score").asLong()).isEqualTo(300);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-019] top N 3 con 4 membri: il ranking di gestione ne mostra 3")
    void topN() {
        String code = board("TB-GAM-LDB-019", "PTS_EARNED", "MONTH", 3, null);
        for (int i = 1; i <= 4; i++) earned(member("ACTIVE"), "PTS", 10L * i, T.plusSeconds(i));
        assertThat(ranking(code)).hasSize(3);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-020] portale: solo nickname, isMe e la mia posizione; nessun identificativo degli altri")
    void portalView() {
        String code = board("TB-GAM-LDB-020", "PTS_EARNED", "MONTH", 10, null);
        String me = member("ACTIVE");
        String other = member("ACTIVE");
        earned(other, "PTS", 200, T);
        earned(me, "PTS", 100, T.plusSeconds(1));
        CLOCK.set(T.plusSeconds(3600));
        JsonNode p = ok("GET", "/v1/portal/leaderboards/" + code + "?memberId=" + me, null, null, 200);
        assertThat(p.path("periodKey").asString()).isEqualTo("2026-09");
        assertThat(p.path("top").size()).isEqualTo(2);
        assertThat(p.path("top").get(0).path("nickname").asString()).isEqualTo("Socio " + other.substring(4));
        assertThat(p.path("top").get(0).path("isMe").asBoolean()).isFalse();
        assertThat(p.path("top").get(1).path("isMe").asBoolean()).isTrue();
        assertThat(p.path("me").path("rank").asInt()).isEqualTo(2);
        assertThat(p.path("me").path("score").asLong()).isEqualTo(100);
        assertThat(p.toString()).doesNotContain(other).doesNotContain("memberId");
    }

    @Test
    @DisplayName("[TB-GAM-LDB-021] portale: membro fuori dalla top N vede comunque la sua posizione")
    void portalOutsideTop() {
        String code = board("TB-GAM-LDB-021", "PTS_EARNED", "MONTH", 3, null);
        for (int i = 1; i <= 3; i++) earned(member("ACTIVE"), "PTS", 100L * i, T.plusSeconds(i));
        String me = member("ACTIVE");
        earned(me, "PTS", 1, T.plusSeconds(10));
        CLOCK.set(T.plusSeconds(3600));
        JsonNode p = ok("GET", "/v1/portal/leaderboards/" + code + "?memberId=" + me, null, null, 200);
        assertThat(p.path("top").size()).isEqualTo(3);
        assertThat(p.path("me").path("rank").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("[TB-GAM-LDB-022] portale: membro senza punteggio, nessuna posizione")
    void portalNotRanked() {
        String code = board("TB-GAM-LDB-022", "PTS_EARNED", "MONTH", 3, null);
        earned(member("ACTIVE"), "PTS", 10, T);
        CLOCK.set(T.plusSeconds(3600));
        JsonNode p = ok("GET", "/v1/portal/leaderboards/" + code + "?memberId=" + member("ACTIVE"), null, null, 200);
        assertThat(p.path("me").isNull() || p.path("me").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-023] classifica INACTIVE: non accumula punteggi")
    void inactiveBoard() {
        String code = board("TB-GAM-LDB-023", "PTS_EARNED", "MONTH", 10, null);
        ok("PUT", "/v1/leaderboards/" + code, actor("MARKETING"), Map.of("status", "INACTIVE"), 200);
        String m = member("ACTIVE");
        earned(m, "PTS", 100, T);
        assertThat(score(code, "2026-09", m)).isNull();
    }

    @Test
    @DisplayName("[TB-GAM-LDB-024] wallet.points.earned dal listener di lh.facts.v1, consegnato due volte: +75 una volta")
    void throughListener() {
        String code = board("TB-GAM-LDB-024", "PTS_EARNED", "ALL_TIME", 10, null);
        String m = member("ACTIVE");
        var e = event(FACT + "wallet.points.earned", m, T, json("{\"currency\":\"PTS\",\"amount\":75,\"balanceAfter\":75}"));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lh.facts.v1", 0, 0L, m, mapper.writeValueAsString(e));
        factsListener.onFact(record, () -> { });
        factsListener.onFact(record, () -> { });
        assertThat(score(code, "ALL", m)).isEqualTo(75);
    }

    // ---------- configurazione ----------

    // TESTBOOK: ambiguo, vedi TB-GAM-LCF-001, TB-GAM-LCF-004 (limiti di top N) e TB-GAM-LCF-015 (prefisso del codice)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/classifiche-configurazione.csv", numLinesToSkip = 1)
    void configurazione(String id, String desc, String role, String overrides, int expHttp) {
        String code = "LDB-" + id.substring("TB-GAM-".length());
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Classifica " + code, "metric", "PTS_EARNED",
                "period", "MONTH", "topN", 10));
        TestbookGamAchievementIT.applyOverrides(body, overrides, () -> {
            ok("POST", "/v1/leaderboards", actor("MARKETING"), Map.of("code", code + "-D", "name", "Doppia", "metric", "PTS_EARNED",
                    "period", "MONTH", "topN", 10), 201);
            return code + "-D";
        });
        Resp r = call("POST", "/v1/leaderboards", actor(role), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 422) assertThat(r.code()).isEqualTo("LEADERBOARD_INVALID");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-LCF-020 (metrica non modificabile: ramo senza specifica)
    @Test
    @DisplayName("[TB-GAM-LCF-020] cambio della metrica: 409 LEADERBOARD_LOCKED")
    void metricLocked() {
        String code = board("TB-GAM-LCF-020", "PTS_EARNED", "MONTH", 10, null);
        Resp r = call("PUT", "/v1/leaderboards/" + code, actor("MARKETING"), Map.of("metric", "STS_EARNED"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("LEADERBOARD_LOCKED");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-LCF-021
    @Test
    @DisplayName("[TB-GAM-LCF-021] cambio del periodo: 409 LEADERBOARD_LOCKED")
    void periodLocked() {
        String code = board("TB-GAM-LCF-021", "PTS_EARNED", "MONTH", 10, null);
        Resp r = call("PUT", "/v1/leaderboards/" + code, actor("MARKETING"), Map.of("period", "EDITION"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("LEADERBOARD_LOCKED");
    }

    @Test
    @DisplayName("[TB-GAM-LCF-022] cambio del top N: 200")
    void topNUpdate() {
        String code = board("TB-GAM-LCF-022", "PTS_EARNED", "MONTH", 10, null);
        assertThat(ok("PUT", "/v1/leaderboards/" + code, actor("MARKETING"), Map.of("topN", 5), 200).path("topN").asInt()).isEqualTo(5);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-LCF-023
    @Test
    @DisplayName("[TB-GAM-LCF-023] cambio del codice: 409 CODE_IMMUTABLE")
    void boardCodeImmutable() {
        String code = board("TB-GAM-LCF-023", "PTS_EARNED", "MONTH", 10, null);
        Resp r = call("PUT", "/v1/leaderboards/" + code, actor("MARKETING"), Map.of("code", code + "-B"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    // ---------- supporto ----------

    private String board(String rowId, String metric, String period, int topN, List<String> types) {
        String code = "LDB-" + rowId.substring("TB-GAM-".length()) + "-" + next();
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Classifica " + code, "metric", metric,
                "period", period, "topN", topN));
        if (types != null) body.put("actionTypes", types);
        ok("POST", "/v1/leaderboards", actor("MARKETING"), body, 201);
        return code;
    }

    /** Fatto {@code wallet.points.earned} elaborato adesso = {@code at} (il parimerito usa l'ora di elaborazione). */
    private void earned(String memberId, String currency, long amount, Instant at) {
        CLOCK.set(at);
        leaderboardService.onPointsEarned(event(FACT + "wallet.points.earned", memberId, at,
                json("{\"currency\":\"" + currency + "\",\"amount\":" + amount + "}")));
        CLOCK.reset();
    }

    private void action(String memberId, String type, Instant at) {
        CLOCK.set(at);
        leaderboardService.onAction(event(ACTION + type, memberId, at, mapper.createObjectNode()));
        CLOCK.reset();
    }

    private Long score(String code, String periodKey, String memberId) {
        List<Long> s = jdbc.sql("""
                        SELECT s.score FROM leaderboard_score s JOIN leaderboard l ON l.id = s.leaderboard_id
                        WHERE l.code = ? AND s.period_key = ? AND s.member_id = ?
                        """).params(code, periodKey, memberId).query(Long.class).list();
        return s.isEmpty() ? null : s.getFirst();
    }

    private List<JsonNode> ranking(String code) {
        List<JsonNode> out = new java.util.ArrayList<>();
        get("/v1/leaderboards/" + code + "/ranking?periodKey=2026-09").path("items").forEach(out::add);
        return out;
    }

    private List<String> rankingMembers(String code) {
        return ranking(code).stream().map(i -> i.path("memberId").asString()).toList();
    }

    private void excluded(String rowId, String status) {
        String code = board(rowId, "PTS_EARNED", "MONTH", 10, null);
        String active = member("ACTIVE");
        String other = member(status);
        earned(other, "PTS", 1000, T);
        earned(active, "PTS", 10, T.plusSeconds(1));
        assertThat(rankingMembers(code)).containsExactly(active);
        assertThat(ranking(code).get(0).path("rank").asInt()).isEqualTo(1);
    }
}
