package io.loyaltyhub.gamification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — premi e consegna ({@code PRZ}) e istante piantato della demo ({@code PLT})
 * (docs/testbook/TB-GAM-gioco.md §5, §6). Oracolo: F-IW-02, F-IW-06..08, docs/03 §6, gamification §2–§4, contratto
 * {@code fact.contest.won}, docs/06 §3, docs/08 §2 ({@code delivery.handle}, {@code demo.admin}), Q-62.
 */
class TestbookGamPrizeIT extends TestbookGamBase {

    // ---------- vincita per tipo di premio ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/premio-vincita.csv", numLinesToSkip = 1)
    void vincitaPerTipo(String id, String desc, String type, String points, String rewardCode, String expDelivery) {
        CLOCK.set(T0);
        String code = contestCode(id);
        Map<String, Object> p = prize("PRZ-X", type, opt(points) == null ? null : Long.parseLong(points), opt(rewardCode), 3);
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), Map.of("prizes", List.of(p)));
        matureOne(contest, "PRZ-X", T0.minusSeconds(1));
        String m = member("ACTIVE");
        Resp r = play(code, m);
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        JsonNode prize = r.body().path("prize");
        assertThat(r.body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(prize.path("code").asString()).isEqualTo("PRZ-X");
        assertThat(prize.path("type").asString()).isEqualTo(type);
        JsonNode won = facts("contest.won", m).getFirst().path("data");
        assertThat(won.path("prizeType").asString()).isEqualTo(type);
        assertThat(won.path("prizeName").asString()).isEqualTo("Premio PRZ-X");
        assertThat(won.path("contestCode").asString()).isEqualTo(code);
        if (opt(points) != null) {
            assertThat(prize.path("points").asLong()).isEqualTo(Long.parseLong(points));
            assertThat(won.path("points").asLong()).isEqualTo(Long.parseLong(points));
        } else {
            assertThat(won.has("points")).isFalse();
        }
        if (opt(rewardCode) != null) {
            assertThat(prize.path("rewardCode").asString()).isEqualTo(rewardCode);
            assertThat(won.path("rewardCode").asString()).isEqualTo(rewardCode);
        } else {
            assertThat(won.has("rewardCode")).isFalse();
        }
        assertThat(delivery(r.body().path("playId").asString())).isEqualTo(expDelivery);
    }

    // ---------- validazione del montepremi ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/premio-validazione.csv", numLinesToSkip = 1)
    void validazione(String id, String desc, String type, String points, String rewardCode, String qty, int expHttp) {
        CLOCK.set(T0);
        String code = contestCode(id);
        Map<String, Object> body = contestBody(code, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)));
        body.put("prizes", List.of(prize("PRZ-V", opt(type), opt(points) == null ? null : Long.parseLong(points),
                opt(rewardCode) == null ? null : rewardCode, opt(qty) == null ? null : Integer.parseInt(qty))));
        Resp r = call("POST", "/v1/contests", actor("MARKETING"), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 422) {
            assertThat(r.code()).isEqualTo("CONTEST_INVALID");
            assertThat(count("SELECT count(*) FROM contest WHERE code = ?", code)).isZero();
        } else {
            assertThat(r.body().path("prizes").get(0).path("quantityRemaining").asInt()).isEqualTo(Integer.parseInt(qty));
        }
    }

    @Test
    @DisplayName("[TB-GAM-PRZ-018] due premi con lo stesso codice")
    void duplicatePrizeCodes() {
        String code = contestCode("TB-GAM-PRZ-018");
        Map<String, Object> body = contestBody(code, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)));
        body.put("prizes", List.of(prize("PRZ-D", "PHYSICAL", null, null, 1), prize("PRZ-D", "PHYSICAL", null, null, 2)));
        Resp r = call("POST", "/v1/contests", actor("MARKETING"), body);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("CONTEST_INVALID");
    }

    @Test
    @DisplayName("[TB-GAM-PRZ-019] premio senza nome")
    void prizeWithoutName() {
        String code = contestCode("TB-GAM-PRZ-019");
        Map<String, Object> body = contestBody(code, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)));
        Map<String, Object> p = prize("PRZ-N", "PHYSICAL", null, null, 1);
        p.put("name", " ");
        body.put("prizes", List.of(p));
        Resp r = call("POST", "/v1/contests", actor("MARKETING"), body);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("CONTEST_INVALID");
    }

    // ---------- consegna manuale dei premi fisici ----------

    // TESTBOOK: ambiguo, vedi TB-GAM-PRZ-034 (nota di soli spazi)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/premio-consegna.csv", numLinesToSkip = 1)
    void consegna(String id, String desc, String win, String role, String status, String note, String expHttp,
                  String expDelivery, String expNote) {
        String playId = "UNKNOWN".equals(win) ? "PL-NON-ESISTE" : winningPlay(id, win);
        Map<String, Object> body = new HashMap<>();
        body.put("status", opt(status));
        body.put("note", opt(note) == null ? null : note);
        Resp r = call("POST", "/v1/plays/" + playId + "/delivery", actor(role), body);
        List<Integer> allowed = Arrays.stream(expHttp.split("/")).map(Integer::parseInt).toList();
        assertThat(r.status()).as(r.text()).isIn(allowed);
        if (opt(expDelivery) != null) {
            assertThat(delivery(playId)).isEqualTo(expDelivery);
        }
        if ("NULL".equals(expNote)) {
            assertThat(note(playId)).isNull();
        } else if (opt(expNote) != null) {
            assertThat(note(playId)).isEqualTo(expNote);
            JsonNode w = winner(playId);
            assertThat(w.path("deliveryStatus").asString()).isEqualTo(expDelivery);
            assertThat(w.path("deliveryNote").asString()).isEqualTo(expNote);
        }
    }

    @Test
    @DisplayName("[TB-GAM-PRZ-035] la consegna scrive una voce di audit con prima e dopo")
    void deliveryAudit() {
        String playId = winningPlay("TB-GAM-PRZ-035", "PHYSICAL");
        ok("POST", "/v1/plays/" + playId + "/delivery", actor("CARE"), Map.of("status", "DELIVERED", "note", "Consegnato"), 204);
        List<JsonNode> audit = audits("PLAY:" + playId);
        assertThat(audit).hasSize(1);
        JsonNode e = audit.getFirst();
        assertThat(e.path("lhactor").asString()).isEqualTo(actor("CARE"));
        assertThat(e.path("data").path("before").path("deliveryStatus").asString()).isEqualTo("PENDING");
        assertThat(e.path("data").path("after").path("deliveryStatus").asString()).isEqualTo("DELIVERED");
    }

    // ---------- istante piantato (demo) ----------

    @Test
    @DisplayName("[TB-GAM-PLT-001] nessun istante maturo: piantato a adesso − 1 s e la giocata vince quel premio")
    void plantNow() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-001");
        String contest = twoPrizeContest(code, T0.plus(Duration.ofDays(1)));
        JsonNode planted = plant(contest, "PRZ-A", 200);
        assertThat(Instant.parse(planted.path("instantAt").asString())).isEqualTo(T0.minusSeconds(1));
        assertThat(jdbc.sql("SELECT planted FROM winning_instant WHERE id = ?").param(planted.path("instantId").asString())
                .query(Boolean.class).single()).isTrue();
        Resp r = play(code, member("ACTIVE"));
        assertThat(r.body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(r.body().path("prize").path("code").asString()).isEqualTo("PRZ-A");
    }

    @Test
    @DisplayName("[TB-GAM-PLT-002] istante maturo più vecchio di un altro premio: vince comunque il premio piantato (Q-62)")
    void plantBeforeOlder() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-002");
        String contest = twoPrizeContest(code, T0.plus(Duration.ofDays(1)));
        Instant older = T0.minus(Duration.ofHours(1));
        String olderB = matureOne(contest, "PRZ-B", older);
        JsonNode planted = plant(contest, "PRZ-A", 200);
        assertThat(Instant.parse(planted.path("instantAt").asString())).isEqualTo(older.minusSeconds(1));
        Resp r = play(code, member("ACTIVE"));
        assertThat(r.body().path("prize").path("code").asString()).isEqualTo("PRZ-A");
        assertThat(instantStatus(olderB)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("[TB-GAM-PLT-003] montepremi invariato: stessi istanti per premio, stessi aperti, stesse quantità")
    void plantKeepsPool() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-003");
        String contest = twoPrizeContest(code, T0.plus(Duration.ofDays(1)));
        String before = poolSnapshot(contest);
        plant(contest, "PRZ-A", 200);
        assertThat(poolSnapshot(contest)).isEqualTo(before);
    }

    @Test
    @DisplayName("[TB-GAM-PLT-004] si sposta l'ultimo istante OPEN dello stesso premio")
    void plantMovesLast() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-004");
        Map<String, Object> body = contestBody(code, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)));
        body.put("prizes", List.of(prize("PRZ-A", "POINTS", 5L, null, 4), prize("PRZ-B", "PHYSICAL", null, null, 4)));
        String contest = createContest(body);
        generate(contest);
        setStatus(contest, "LIVE");
        String last = jdbc.sql("""
                        SELECT w.id FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                        WHERE w.contest_id = ? AND p.code = 'PRZ-A' ORDER BY w.instant_at DESC, w.id DESC LIMIT 1
                        """).param(contest).query(String.class).single();
        JsonNode planted = plant(contest, "PRZ-A", 200);
        assertThat(planted.path("instantId").asString()).isEqualTo(last);
    }

    @Test
    @DisplayName("[TB-GAM-PLT-005] due istanti piantati per lo stesso premio: due giocate vincono entrambe")
    void plantTwice() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-005");
        String contest = twoPrizeContest(code, T0.plus(Duration.ofDays(1)));
        String first = plant(contest, "PRZ-A", 200).path("instantId").asString();
        String second = plant(contest, "PRZ-A", 200).path("instantId").asString();
        assertThat(second).isNotEqualTo(first);
        String m = member("ACTIVE");
        grant(m, contest, 1);
        assertThat(play(code, m).body().path("prize").path("code").asString()).isEqualTo("PRZ-A");
        assertThat(play(code, m).body().path("prize").path("code").asString()).isEqualTo("PRZ-A");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLT-006 (concorso non LIVE: nessuna fonte)
    @Test
    @DisplayName("[TB-GAM-PLT-006] concorso DRAFT: 409 CONTEST_NOT_LIVE")
    void plantDraft() {
        CLOCK.set(T0);
        String contest = contestIn(contestCode("TB-GAM-PLT-006"), "DRAFT", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(10)), null, null);
        Resp r = plantResp(contest, "PTS-10", actor("ADMIN"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CONTEST_NOT_LIVE");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLT-007
    @Test
    @DisplayName("[TB-GAM-PLT-007] concorso PAUSED: 409 CONTEST_NOT_LIVE")
    void plantPaused() {
        CLOCK.set(T0);
        String contest = contestIn(contestCode("TB-GAM-PLT-007"), "PAUSED", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(10)), null, null);
        Resp r = plantResp(contest, "PTS-10", actor("ADMIN"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CONTEST_NOT_LIVE");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLT-008
    @Test
    @DisplayName("[TB-GAM-PLT-008] premio non presente nel concorso: 422 PRIZE_NOT_FOUND")
    void plantUnknownPrize() {
        CLOCK.set(T0);
        String contest = twoPrizeContest(contestCode("TB-GAM-PLT-008"), T0.plus(Duration.ofDays(1)));
        Resp r = plantResp(contest, "PRZ-ZZ", actor("ADMIN"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("PRIZE_NOT_FOUND");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLT-009
    @Test
    @DisplayName("[TB-GAM-PLT-009] nessun istante OPEN per il premio: 422 NO_OPEN_INSTANT")
    void plantNoOpen() {
        CLOCK.set(T0);
        String contest = twoPrizeContest(contestCode("TB-GAM-PLT-009"), T0.plus(Duration.ofDays(1)));
        jdbc.sql("UPDATE winning_instant SET status = 'CLAIMED' WHERE prize_id = ?").param(prizeId(contest, "PRZ-A")).update();
        Resp r = plantResp(contest, "PRZ-A", actor("ADMIN"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("NO_OPEN_INSTANT");
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/pianta-ruoli.csv", numLinesToSkip = 1)
    void piantaRuoli(String id, String desc, String role, int expHttp) {
        CLOCK.set(T0);
        String contest = twoPrizeContest(contestCode(id), T0.plus(Duration.ofDays(1)));
        Resp r = plantResp(contest, "PRZ-A", actor(role));
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        long planted = count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND planted", contest);
        assertThat(planted).isEqualTo(expHttp == 200 ? 1 : 0);
    }

    @Test
    @DisplayName("[TB-GAM-PLT-016] l'istante piantato scrive una voce di audit")
    void plantAudit() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-PLT-016");
        String contest = twoPrizeContest(code, T0.plus(Duration.ofDays(1)));
        JsonNode planted = plant(contest, "PRZ-A", 200);
        List<JsonNode> audit = audits("CONTEST:" + code).stream()
                .filter(a -> a.path("data").path("after").path("planted").asBoolean(false)).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().path("data").path("after").path("instantId").asString())
                .isEqualTo(planted.path("instantId").asString());
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-PLT-017 (premio non indicato)
    @Test
    @DisplayName("[TB-GAM-PLT-017] richiesta senza prizeCode: 422 PRIZE_NOT_FOUND")
    void plantWithoutPrize() {
        CLOCK.set(T0);
        String contest = twoPrizeContest(contestCode("TB-GAM-PLT-017"), T0.plus(Duration.ofDays(1)));
        Resp r = call("POST", "/v1/demo/contests/" + contest + "/plant-instant", actor("ADMIN"), Map.of());
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("PRIZE_NOT_FOUND");
    }

    // ---------- supporto ----------

    private String twoPrizeContest(String code, Instant instantsAt) {
        return contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(10)), instantsAt,
                Map.of("prizes", List.of(prize("PRZ-A", "POINTS", 50L, null, 3), prize("PRZ-B", "PHYSICAL", null, null, 3))));
    }

    private JsonNode plant(String contest, String prizeCode, int expected) {
        return ok("POST", "/v1/demo/contests/" + contest + "/plant-instant", actor("ADMIN"), Map.of("prizeCode", prizeCode), expected);
    }

    private Resp plantResp(String contest, String prizeCode, String actor) {
        return call("POST", "/v1/demo/contests/" + contest + "/plant-instant", actor, Map.of("prizeCode", prizeCode));
    }

    private String poolSnapshot(String contest) {
        List<String> rows = new ArrayList<>();
        jdbc.sql("""
                        SELECT p.code, p.quantity_total, p.quantity_remaining,
                          (SELECT count(*) FROM winning_instant w WHERE w.prize_id = p.id) AS total,
                          (SELECT count(*) FROM winning_instant w WHERE w.prize_id = p.id AND w.status = 'OPEN') AS open
                        FROM prize p WHERE p.contest_id = ? ORDER BY p.code
                        """).param(contest)
                .query((rs, n) -> rows.add(rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getInt(3) + ":" + rs.getLong(4)
                        + ":" + rs.getLong(5)))
                .list();
        return String.join(",", rows);
    }

    /** Giocata del tipo indicato: vincita del premio {@code win} oppure {@code LOSE}. */
    private String winningPlay(String rowId, String win) {
        CLOCK.set(T0);
        String code = contestCode(rowId);
        Map<String, Object> p = switch (win) {
            case "POINTS" -> prize("PRZ-W", "POINTS", 20L, null, 2);
            case "COUPON" -> prize("PRZ-W", "COUPON", null, "RWD-COFFEE", 2);
            default -> prize("PRZ-W", "PHYSICAL", null, null, 2);
        };
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), Map.of("prizes", List.of(p)));
        if (!"LOSE".equals(win)) {
            matureOne(contest, "PRZ-W", T0.minusSeconds(1));
        }
        Resp r = play(code, member("ACTIVE"));
        assertThat(r.body().path("outcome").asString()).isEqualTo("LOSE".equals(win) ? "LOSE" : "WIN");
        CLOCK.reset();
        return r.body().path("playId").asString();
    }

    private String delivery(String playId) {
        return jdbc.sql("SELECT delivery_status FROM play WHERE id = ?").param(playId).query(String.class).single();
    }

    private String note(String playId) {
        return jdbc.sql("SELECT delivery_note FROM play WHERE id = ?").param(playId).query(String.class).list().getFirst();
    }

    private JsonNode winner(String playId) {
        String contest = jdbc.sql("SELECT contest_id FROM play WHERE id = ?").param(playId).query(String.class).single();
        for (JsonNode w : get("/v1/contests/" + contest + "/winners")) {
            if (playId.equals(w.path("playId").asString())) return w;
        }
        throw new AssertionError("vincita assente: " + playId);
    }
}
