package io.loyaltyhub.gamification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — ciclo di vita del concorso con approvazione LEGAL ({@code LFC}: stato × azione, {@code ROL}:
 * azione × ruolo) (docs/testbook/TB-GAM-gioco.md §7). Oracolo: docs/03 §3.6, docs/06 §2, §3, §7, docs/08 §2, §3.3,
 * gamification §3 ({@code INSTANTS_NOT_GENERATED}), docs/03 §6 (fine concorso).
 */
class TestbookGamLifecycleIT extends TestbookGamBase {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/ciclo-stato-azione.csv", numLinesToSkip = 1)
    void statoAzione(String id, String desc, String from, String action, String role, String comment, int expHttp,
                     String expStatus) {
        transition(id, from, action, role, comment, expHttp, expStatus);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/ciclo-ruolo-azione.csv", numLinesToSkip = 1)
    void ruoloAzione(String id, String desc, String from, String action, String role, String comment, int expHttp,
                     String expStatus) {
        transition(id, from, action, role, comment, expHttp, expStatus);
    }

    @Test
    @DisplayName("[TB-GAM-LFC-057] PUBLISH da APPROVED senza istanti generati: 422 INSTANTS_NOT_GENERATED")
    void publishWithoutInstants() {
        String contest = createContest(contestBody(contestCode("TB-GAM-LFC-057"), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9))));
        setStatus(contest, "APPROVED");
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "PUBLISH"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSTANTS_NOT_GENERATED");
        assertThat(status(contest)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-058] APPROVED con istanti, poi cambio del periodo: PUBLISH 422 INSTANTS_NOT_GENERATED")
    void publishAfterPeriodChange() {
        String contest = contestIn(contestCode("TB-GAM-LFC-058"), "APPROVED", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(9)), null, null);
        ok("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("endAt", T0.plus(Duration.ofDays(12)).toString()), 200);
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "PUBLISH"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INSTANTS_NOT_GENERATED");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-059] REJECT senza commento: 422 e resta IN_REVIEW")
    void rejectWithoutComment() {
        String contest = contestIn(contestCode("TB-GAM-LFC-059"), "IN_REVIEW", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(9)), null, null);
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor("LEGAL"), Map.of("action", "REJECT"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(status(contest)).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-060] REJECT con commento di soli spazi: 422 e resta IN_REVIEW")
    void rejectBlankComment() {
        String contest = contestIn(contestCode("TB-GAM-LFC-060"), "IN_REVIEW", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(9)), null, null);
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor("LEGAL"), Map.of("action", "REJECT", "comment", "   "));
        assertThat(r.status()).isEqualTo(422);
        assertThat(status(contest)).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-061] END da LIVE: istanti OPEN → VOID, CLAIMED invariati")
    void endVoidsOpen() {
        endVoids("TB-GAM-LFC-061", "LIVE");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-062] END da PAUSED: istanti OPEN → VOID, CLAIMED invariati")
    void endFromPausedVoidsOpen() {
        endVoids("TB-GAM-LFC-062", "PAUSED");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-063] ogni transizione scrive storico (chi, commento), fatto contest.status.changed e audit")
    void transitionTrail() {
        String code = contestCode("TB-GAM-LFC-063");
        String contest = contestIn(code, "DRAFT", T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9)), null, null);
        ok("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "SUBMIT", "comment", "pronto"), 200);
        JsonNode history = get("/v1/contests/" + contest + "/approval-history");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).path("actor").asString()).isEqualTo(actor("MARKETING"));
        assertThat(history.get(0).path("comment").asString()).isEqualTo("pronto");
        assertThat(history.get(0).path("fromStatus").asString()).isEqualTo("DRAFT");
        assertThat(history.get(0).path("toStatus").asString()).isEqualTo("IN_REVIEW");
        List<JsonNode> facts = outbox(FACT + "contest.status.changed", "contest:" + code);
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().path("data").path("previousStatus").asString()).isEqualTo("DRAFT");
        assertThat(facts.getFirst().path("data").path("newStatus").asString()).isEqualTo("IN_REVIEW");
        assertThat(audits("CONTEST:" + code).stream().filter(a -> "TRANSITION".equals(a.path("data").path("action").asString())))
                .hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-LFC-064] APPROVE da ADMIN al posto di LEGAL: override marcato in audit")
    void adminOverrideAudited() {
        String code = contestCode("TB-GAM-LFC-064");
        String contest = contestIn(code, "IN_REVIEW", T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9)), null, null);
        ok("POST", "/v1/contests/" + contest + "/transitions", actor("ADMIN"), Map.of("action", "APPROVE"), 200);
        JsonNode audit = audits("CONTEST:" + code).stream()
                .filter(a -> "TRANSITION".equals(a.path("data").path("action").asString())).findFirst().orElseThrow();
        assertThat(audit.path("data").path("summary").asString()).containsIgnoringCase("override");
    }

    @Test
    @DisplayName("[TB-GAM-LFC-065] coda approvazioni: il concorso IN_REVIEW compare con ruolo LEGAL, dopo APPROVE no")
    void approvalQueue() {
        String code = contestCode("TB-GAM-LFC-065");
        String contest = contestIn(code, "DRAFT", T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9)), null, null);
        ok("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "SUBMIT"), 200);
        JsonNode item = queueItem(code);
        assertThat(item).isNotNull();
        assertThat(item.path("entityType").asString()).isEqualTo("CONTEST");
        assertThat(item.path("requiredRole").asString()).isEqualTo("LEGAL");
        assertThat(item.path("submittedBy").asString()).isEqualTo(actor("MARKETING"));
        ok("POST", "/v1/contests/" + contest + "/transitions", actor("LEGAL"), Map.of("action", "APPROVE"), 200);
        assertThat(queueItem(code)).isNull();
    }

    // Q-282: azione sconosciuta → 422 INVALID_ACTION (non 400), comune a campagne, premi e concorsi.
    @Test
    @DisplayName("[TB-GAM-LFC-066] azione sconosciuta LAUNCH: 422 INVALID_ACTION")
    void unknownAction() {
        String contest = contestIn(contestCode("TB-GAM-LFC-066"), "DRAFT", T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofDays(9)), null, null);
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "LAUNCH"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("INVALID_ACTION");
        assertThat(status(contest)).isEqualTo("DRAFT");
    }

    // ---------- supporto ----------

    private void transition(String id, String from, String action, String role, String comment, int expHttp, String expStatus) {
        String code = contestCode(id);
        String contest = contestIn(code, from, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9)), null, null);
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        if (opt(comment) != null) body.put("comment", comment);
        Resp r = call("POST", "/v1/contests/" + contest + "/transitions", actor(role), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 200) {
            assertThat(r.body().path("status").asString()).isEqualTo(expStatus);
        }
        assertThat(status(contest)).isEqualTo(expStatus);
        long changed = outbox(FACT + "contest.status.changed", "contest:" + code).size();
        assertThat(changed).as("fatto contest.status.changed").isEqualTo(expHttp == 200 ? 1 : 0);
    }

    private void endVoids(String rowId, String from) {
        CLOCK.set(T0);
        String code = contestCode(rowId);
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9)),
                T0.plus(Duration.ofDays(2)), null);
        matureOne(contest, null, T0.minusSeconds(1));
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("WIN");
        if (!"LIVE".equals(from)) setStatus(contest, from);
        ok("POST", "/v1/contests/" + contest + "/transitions", actor("MARKETING"), Map.of("action", "END"), 200);
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'OPEN'", contest)).isZero();
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'VOID'", contest)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'CLAIMED'", contest)).isEqualTo(1);
    }

    private JsonNode queueItem(String code) {
        for (JsonNode i : get("/v1/approvals")) {
            if (code.equals(i.path("code").asString()) && "IN_REVIEW".equals(i.path("status").asString())) return i;
        }
        return null;
    }
}
