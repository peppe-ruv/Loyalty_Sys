package io.loyaltyhub.gamification;

import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.gamification.application.AchievementService;
import io.loyaltyhub.gamification.application.LeaderboardService;
import io.loyaltyhub.gamification.messaging.BadgeAwardHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testbook TB-GAM — obiettivi, badge e referral lato gioco ({@code PRG}, {@code ACF}, {@code BDG}, {@code REF})
 * (docs/testbook/TB-GAM-gioco.md §13–§15, §20). Le azioni entrano dal servizio applicativo come le consegnerebbe il
 * listener di {@code lh.actions.v1}; i fatti prodotti si leggono dall'outbox. Oracolo: docs/03 §2, §8, gamification
 * §2–§5, §7, F-ACH-01..03, docs/08 §2 e BO-15, contratti {@code achievement.*} e {@code badge.awarded}.
 */
class TestbookGamAchievementIT extends TestbookGamBase {

    @Autowired
    private AchievementService achievementService;
    @Autowired
    private LeaderboardService leaderboardService;
    @Autowired
    private BadgeAwardHandler badgeAwardHandler;

    // TESTBOOK: ambiguo, vedi TB-GAM-PRG-013 (membro senza snapshot), TB-GAM-PRG-018 (troncamento di SUM),
    // TB-GAM-PRG-021 (importo negativo)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/obiettivi-progresso.csv", numLinesToSkip = 1, quoteCharacter = '\'')
    void progresso(String id, String desc, String metric, long target, String period, boolean repeatable, String achStatus,
                   String memberStatus, String types, String sumField, String streakUnit, String filter, String actions,
                   int expProgressed, int expCompleted, String expValue, String expPeriodKey) {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        String code = "ACH-" + id.substring("TB-GAM-".length());
        Map<String, Object> body = achievementBody(code, symbols(id, types), metric, target, period);
        body.put("repeatable", repeatable);
        if (opt(sumField) != null) body.put("sumField", sumField);
        if (opt(streakUnit) != null) body.put("streakUnit", streakUnit);
        if (opt(filter) != null) body.put("filter", json(filter));
        ok("POST", "/v1/achievements", actor("MARKETING"), body, 201);
        if ("INACTIVE".equals(achStatus)) {
            ok("PUT", "/v1/achievements/" + code, actor("MARKETING"), Map.of("status", "INACTIVE"), 200);
        }
        String m = member(memberStatus);
        for (String a : actions.split(";")) {
            String[] symAndRest = a.split("@", 2);
            String[] timeAndAmount = symAndRest[1].split("=", 2);
            ObjectNode data = mapper.createObjectNode();
            if (timeAndAmount.length > 1 && !"miss".equals(timeAndAmount[1])) {
                data.put("amount", new BigDecimal(timeAndAmount[1]));
            }
            achievementService.onAction(event(ACTION + type(id, symAndRest[0]), m, Instant.parse(timeAndAmount[0]), data));
        }
        List<JsonNode> progressed = byCode(facts("achievement.progressed", m), code);
        assertThat(progressed).as("achievement.progressed").hasSize(expProgressed);
        progressed.forEach(p -> {
            assertThat(p.path("data").path("target").asLong()).isEqualTo(target);
            assertThat(p.path("data").path("value").asLong()).isBetween(0L, target);
        });
        assertThat(byCode(facts("achievement.completed", m), code)).as("achievement.completed").hasSize(expCompleted);
        if (opt(expValue) != null) {
            Long value = jdbc.sql("""
                            SELECT p.value FROM achievement_progress p JOIN achievement a ON a.id = p.achievement_id
                            WHERE a.code = ? AND p.member_id = ? AND p.period_key = ?
                            """).params(code, m, expPeriodKey).query(Long.class).single();
            assertThat(value).isEqualTo(Long.parseLong(expValue));
        } else if (expProgressed == 0) {
            assertThat(count("""
                    SELECT count(*) FROM achievement_progress p JOIN achievement a ON a.id = p.achievement_id
                    WHERE a.code = ? AND p.member_id = ? AND p.value > 0""", code, m)).isZero();
        }
    }

    @Test
    @DisplayName("[TB-GAM-PRG-030] completamento con badge collegato: badge.awarded origine ACHIEVEMENT e badge al membro")
    void completionAwardsBadge() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        String badge = createBadge("BDG-PRG-030");
        Map<String, Object> body = achievementBody("ACH-PRG-030", List.of("tb.prg030.a"), "COUNT", 1, "EVER");
        body.put("badgeCode", badge);
        ok("POST", "/v1/achievements", actor("MARKETING"), body, 201);
        String m = member("ACTIVE");
        achievementService.onAction(event(ACTION + "tb.prg030.a", m, Instant.parse("2026-10-01T10:00:00Z"), mapper.createObjectNode()));
        List<JsonNode> awarded = facts("badge.awarded", m);
        assertThat(awarded).hasSize(1);
        assertThat(awarded.getFirst().path("data").path("badgeCode").asString()).isEqualTo(badge);
        assertThat(awarded.getFirst().path("data").path("origin").asString()).isEqualTo("ACHIEVEMENT");
        assertThat(awarded.getFirst().path("data").path("badgeName").asString()).isNotBlank();
        assertThat(count("SELECT count(*) FROM member_badge WHERE member_id = ? AND badge_code = ? AND origin = 'ACHIEVEMENT'", m, badge))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-PRG-031] badge già posseduto: obiettivo completato ma nessun secondo badge.awarded")
    void badgeAlreadyOwned() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        String badge = createBadge("BDG-PRG-031");
        Map<String, Object> body = achievementBody("ACH-PRG-031", List.of("tb.prg031.a"), "COUNT", 1, "EVER");
        body.put("badgeCode", badge);
        ok("POST", "/v1/achievements", actor("MARKETING"), body, 201);
        String m = member("ACTIVE");
        badgeAwardHandler.handle(event(EFFECT + "badge.award", m, Instant.now(), json("{\"badgeCode\":\"" + badge + "\",\"effectId\":\"EFF-PRG-031\"}")));
        achievementService.onAction(event(ACTION + "tb.prg031.a", m, Instant.parse("2026-10-01T10:00:00Z"), mapper.createObjectNode()));
        assertThat(byCode(facts("achievement.completed", m), "ACH-PRG-031")).hasSize(1);
        assertThat(facts("badge.awarded", m)).hasSize(1);
        assertThat(count("SELECT count(*) FROM member_badge WHERE member_id = ? AND badge_code = ?", m, badge)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-PRG-032] portale: valore, traguardo, percentuale e periodo corrente; completato con data")
    void portalView() {
        CLOCK.set(Instant.parse("2026-09-20T10:00:00Z"));
        Map<String, Object> month = achievementBody("ACH-PRG-032-M", List.of("tb.prg032.a"), "COUNT", 3, "MONTH");
        month.put("repeatable", true);
        ok("POST", "/v1/achievements", actor("MARKETING"), month, 201);
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-PRG-032-E", List.of("tb.prg032.b"), "COUNT", 1, "EVER"), 201);
        String m = member("ACTIVE");
        achievementService.onAction(event(ACTION + "tb.prg032.a", m, Instant.parse("2026-09-02T10:00:00Z"), mapper.createObjectNode()));
        achievementService.onAction(event(ACTION + "tb.prg032.a", m, Instant.parse("2026-09-03T10:00:00Z"), mapper.createObjectNode()));
        achievementService.onAction(event(ACTION + "tb.prg032.b", m, Instant.parse("2026-09-04T10:00:00Z"), mapper.createObjectNode()));
        JsonNode list = ok("GET", "/v1/portal/achievements?memberId=" + m, null, null, 200);
        JsonNode mo = find(list, "ACH-PRG-032-M");
        assertThat(mo.path("value").asLong()).isEqualTo(2);
        assertThat(mo.path("target").asLong()).isEqualTo(3);
        assertThat(mo.path("pct").asInt()).isBetween(66, 67);
        assertThat(mo.path("periodKey").asString()).isEqualTo("2026-09");
        JsonNode ev = find(list, "ACH-PRG-032-E");
        assertThat(ev.path("pct").asInt()).isEqualTo(100);
        assertThat(ev.path("completedAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("[TB-GAM-PRG-033] azione interna achievement.completed elencata tra i tipi: conta")
    void internalActionListed() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        Map<String, Object> body = achievementBody("ACH-PRG-033", List.of("achievement.completed"), "COUNT", 2, "EVER");
        body.put("filter", json("{\"op\":\"all\",\"rules\":[{\"field\":\"data.achievementCode\",\"cmp\":\"eq\",\"value\":\"ACH-PRG-033-SRC\"}]}"));
        ok("POST", "/v1/achievements", actor("MARKETING"), body, 201);
        String m = member("ACTIVE");
        JsonNode data = json("{\"achievementCode\":\"ACH-PRG-033-SRC\"}");
        achievementService.onAction(event(ACTION + "achievement.completed", m, Instant.parse("2026-10-01T10:00:00Z"), data));
        achievementService.onAction(event(ACTION + "achievement.completed", m, Instant.parse("2026-10-02T10:00:00Z"), data));
        assertThat(byCode(facts("achievement.completed", m), "ACH-PRG-033")).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-PRG-034] azione interna achievement.completed non elencata: non conta")
    void internalActionNotListed() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-PRG-034", List.of("tb.prg034.a"), "COUNT", 1, "EVER"), 201);
        String m = member("ACTIVE");
        achievementService.onAction(event(ACTION + "achievement.completed", m, Instant.parse("2026-10-01T10:00:00Z"),
                json("{\"achievementCode\":\"ACH-PRG-034\"}")));
        assertThat(byCode(facts("achievement.progressed", m), "ACH-PRG-034")).isEmpty();
    }

    // ---------- configurazione degli obiettivi ----------

    // TESTBOOK: ambiguo, vedi TB-GAM-ACF-012 (traguardo oltre i tipi) e TB-GAM-ACF-025 (prefisso del codice)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/obiettivi-configurazione.csv", numLinesToSkip = 1)
    void configurazione(String id, String desc, String role, String overrides, int expHttp) {
        String code = "ACH-" + id.substring("TB-GAM-".length());
        Map<String, Object> body = achievementBody(code, List.of("tb.acf"), "COUNT", 3, "EVER");
        applyOverrides(body, overrides, () -> {
            ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody(code + "-D", List.of("tb.acf"), "COUNT", 3, "EVER"), 201);
            return code + "-D";
        });
        Resp r = call("POST", "/v1/achievements", actor(role), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 422) assertThat(r.code()).isEqualTo("ACHIEVEMENT_INVALID");
        if (expHttp == 201) assertThat(get("/v1/achievements/" + body.get("code")).path("code").asString()).isEqualTo(body.get("code"));
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-ACF-033 (immutabilità del codice non specificata per gli obiettivi)
    @Test
    @DisplayName("[TB-GAM-ACF-033] cambio del codice di un obiettivo: 409 CODE_IMMUTABLE")
    void achievementCodeImmutable() {
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-ACF-033", List.of("tb.acf"), "COUNT", 3, "EVER"), 201);
        Resp r = call("PUT", "/v1/achievements/ACH-ACF-033", actor("MARKETING"), Map.of("code", "ACH-ACF-033-B"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    @Test
    @DisplayName("[TB-GAM-ACF-034] modifica del traguardo: 200 e nuovo valore")
    void achievementUpdateTarget() {
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-ACF-034", List.of("tb.acf"), "COUNT", 3, "EVER"), 201);
        JsonNode r = ok("PUT", "/v1/achievements/ACH-ACF-034", actor("MARKETING"), Map.of("target", 5), 200);
        assertThat(r.path("target").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("[TB-GAM-ACF-035] modifica di un obiettivo inesistente: 404")
    void achievementUnknown() {
        assertThat(call("PUT", "/v1/achievements/ACH-NON-ESISTE", actor("MARKETING"), Map.of("target", 5)).status()).isEqualTo(404);
    }

    // ---------- badge ----------

    @Test
    @DisplayName("[TB-GAM-BDG-001] effetto badge.award: badge al membro con origine CAMPAIGN e fatto badge.awarded")
    void badgeFromEffect() {
        String badge = createBadge("BDG-BDG-001");
        String m = member("ACTIVE");
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-001"));
        List<JsonNode> awarded = facts("badge.awarded", m);
        assertThat(awarded).hasSize(1);
        assertThat(awarded.getFirst().path("data").path("origin").asString()).isEqualTo("CAMPAIGN");
        assertThat(awarded.getFirst().path("data").path("badgeName").asString()).isEqualTo("Badge BDG-BDG-001");
        assertThat(count("SELECT count(*) FROM member_badge WHERE member_id = ? AND badge_code = ? AND origin = 'CAMPAIGN'", m, badge))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-002] stesso badge da un secondo effetto: nessun secondo badge né fatto")
    void badgeOnce() {
        String badge = createBadge("BDG-BDG-002");
        String m = member("ACTIVE");
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-002-A"));
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-002-B"));
        assertThat(facts("badge.awarded", m)).hasSize(1);
        assertThat(count("SELECT count(*) FROM member_badge WHERE member_id = ? AND badge_code = ?", m, badge)).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-003] stesso effetto riconsegnato: nulla di nuovo")
    void badgeEffectReplay() {
        String badge = createBadge("BDG-BDG-003");
        String m = member("ACTIVE");
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-003"));
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-003"));
        assertThat(facts("badge.awarded", m)).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-004] badge inesistente nell'effetto: errore non ritentabile BADGE_NOT_FOUND (DLQ)")
    void badgeUnknown() {
        String m = member("ACTIVE");
        assertThatThrownBy(() -> badgeAwardHandler.handle(effect(m, "BDG-NON-ESISTE", "EFF-BDG-004")))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("BADGE_NOT_FOUND"));
        assertThat(facts("badge.awarded", m)).isEmpty();
    }

    // Q-297 DECISA: effetto senza dati → DLQ INVALID_EFFECT
    @Test
    @DisplayName("[TB-GAM-BDG-005] effetto senza dati: errore non ritentabile INVALID_EFFECT")
    void badgeWithoutData() {
        assertThatThrownBy(() -> badgeAwardHandler.handle(event(EFFECT + "badge.award", member("ACTIVE"), Instant.now(), null)))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("INVALID_EFFECT"));
    }

    // Q-297 DECISA: effetto senza membro → DLQ INVALID_EFFECT
    @Test
    @DisplayName("[TB-GAM-BDG-006] effetto senza membro nel subject: errore non ritentabile INVALID_EFFECT")
    void badgeWithoutMember() {
        String badge = createBadge("BDG-BDG-006");
        assertThatThrownBy(() -> badgeAwardHandler.handle(event(EFFECT + "badge.award", null, Instant.now(),
                json("{\"badgeCode\":\"" + badge + "\"}"))))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("INVALID_EFFECT"));
    }

    @Test
    @DisplayName("[TB-GAM-BDG-007] portale: badge ottenuti con data e origine prima di quelli da ottenere")
    void portalBadges() {
        String badge = createBadge("BDG-BDG-007");
        String m = member("ACTIVE");
        badgeAwardHandler.handle(effect(m, badge, "EFF-BDG-007"));
        JsonNode list = ok("GET", "/v1/portal/badges?memberId=" + m, null, null, 200);
        assertThat(list.get(0).path("code").asString()).isEqualTo(badge);
        assertThat(list.get(0).path("awardedAt").asString()).isNotBlank();
        assertThat(list.get(0).path("origin").asString()).isEqualTo("CAMPAIGN");
        JsonNode other = list.get(1);
        assertThat(other.path("awardedAt").isNull() || other.path("awardedAt").isMissingNode()).isTrue();
        assertThat(other.path("unlockHint").asString()).isNotBlank();
    }

    @Test
    @DisplayName("[TB-GAM-BDG-008] badge senza nome: 422 BADGE_INVALID")
    void badgeWithoutName() {
        Resp r = call("POST", "/v1/badges", actor("MARKETING"), Map.of("code", "BDG-BDG-008", "name", " "));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("BADGE_INVALID");
    }

    @Test
    @DisplayName("[TB-GAM-BDG-009] codice badge già usato: 409")
    void badgeDuplicate() {
        createBadge("BDG-BDG-009");
        assertThat(call("POST", "/v1/badges", actor("MARKETING"), Map.of("code", "BDG-BDG-009", "name", "Doppio")).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-010] modifica di un badge inesistente: 404")
    void badgeUpdateUnknown() {
        assertThat(call("PUT", "/v1/badges/BDG-NON-ESISTE", actor("MARKETING"), Map.of("name", "X")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-011] LEGAL non crea badge")
    void badgeLegal() {
        assertThat(call("POST", "/v1/badges", actor("LEGAL"), Map.of("code", "BDG-BDG-011", "name", "No")).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("[TB-GAM-BDG-012] MARKETING crea un badge: in elenco con 0 membri")
    void badgeCreate() {
        createBadge("BDG-BDG-012");
        JsonNode b = find(get("/v1/badges"), "BDG-BDG-012");
        assertThat(b.path("holders").asLong()).isZero();
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-BDG-013 (prefisso del codice badge)
    @Test
    @DisplayName("[TB-GAM-BDG-013] codice badge senza prefisso BDG-: 422 BADGE_INVALID")
    void badgeCodeFormat() {
        Resp r = call("POST", "/v1/badges", actor("MARKETING"), Map.of("code", "MEDAGLIA-TB", "name", "Medaglia"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("BADGE_INVALID");
    }

    @Test
    @DisplayName("[TB-GAM-BDG-014] elenco badge: quanti membri lo hanno")
    void badgeHolders() {
        String badge = createBadge("BDG-BDG-014");
        badgeAwardHandler.handle(effect(member("ACTIVE"), badge, "EFF-BDG-014-A"));
        badgeAwardHandler.handle(effect(member("ACTIVE"), badge, "EFF-BDG-014-B"));
        assertThat(find(get("/v1/badges"), badge).path("holders").asLong()).isEqualTo(2);
    }

    // ---------- referral lato gioco (azione interna referral.completed) ----------

    @Test
    @DisplayName("[TB-GAM-REF-001] obiettivo che elenca referral.completed: l'azione del presentatore lo completa")
    void referralCountsWhenListed() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-REF-001", List.of("referral.completed"), "COUNT", 1, "EVER"), 201);
        String referrer = member("ACTIVE");
        achievementService.onAction(event(ACTION + "referral.completed", referrer, Instant.parse("2026-10-01T10:00:00Z"),
                json("{\"role\":\"REFERRER\",\"referrerId\":\"" + referrer + "\"}")));
        assertThat(byCode(facts("achievement.completed", referrer), "ACH-REF-001")).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-REF-002] filtro sul ruolo REFERRER: conta per il presentatore, non per l'invitato")
    void referralRoleFilter() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        Map<String, Object> body = achievementBody("ACH-REF-002", List.of("referral.completed"), "COUNT", 1, "EVER");
        body.put("filter", json("{\"op\":\"all\",\"rules\":[{\"field\":\"data.role\",\"cmp\":\"eq\",\"value\":\"REFERRER\"}]}"));
        ok("POST", "/v1/achievements", actor("MARKETING"), body, 201);
        String referrer = member("ACTIVE");
        String referee = member("ACTIVE");
        Instant t = Instant.parse("2026-10-01T10:00:00Z");
        achievementService.onAction(event(ACTION + "referral.completed", referee, t, json("{\"role\":\"REFEREE\"}")));
        achievementService.onAction(event(ACTION + "referral.completed", referrer, t, json("{\"role\":\"REFERRER\"}")));
        assertThat(byCode(facts("achievement.completed", referrer), "ACH-REF-002")).hasSize(1);
        assertThat(byCode(facts("achievement.progressed", referee), "ACH-REF-002")).isEmpty();
    }

    @Test
    @DisplayName("[TB-GAM-REF-003] obiettivo che non elenca referral.completed: l'azione interna non conta")
    void referralNotListed() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        ok("POST", "/v1/achievements", actor("MARKETING"), achievementBody("ACH-REF-003", List.of("tb.ref003.a"), "COUNT", 1, "EVER"), 201);
        String referrer = member("ACTIVE");
        achievementService.onAction(event(ACTION + "referral.completed", referrer, Instant.parse("2026-10-01T10:00:00Z"),
                json("{\"role\":\"REFERRER\"}")));
        assertThat(byCode(facts("achievement.progressed", referrer), "ACH-REF-003")).isEmpty();
    }

    @Test
    @DisplayName("[TB-GAM-REF-004] classifica ACTION_COUNT su referral.completed: +1 al presentatore e +1 all'invitato")
    void referralLeaderboard() {
        CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
        ok("POST", "/v1/leaderboards", actor("MARKETING"), Map.of("code", "LDB-REF-004", "name", "Inviti del mese",
                "metric", "ACTION_COUNT", "actionTypes", List.of("referral.completed"), "period", "MONTH", "topN", 10), 201);
        String referrer = member("ACTIVE");
        String referee = member("ACTIVE");
        Instant t = Instant.parse("2026-10-01T10:00:00Z");
        leaderboardService.onAction(event(ACTION + "referral.completed", referrer, t, json("{\"role\":\"REFERRER\"}")));
        leaderboardService.onAction(event(ACTION + "referral.completed", referee, t, json("{\"role\":\"REFEREE\"}")));
        JsonNode items = get("/v1/leaderboards/LDB-REF-004/ranking?periodKey=2026-10").path("items");
        assertThat(items.size()).isEqualTo(2);
        items.forEach(i -> assertThat(i.path("score").asLong()).isEqualTo(1));
    }

    // ---------- supporto ----------

    private static Map<String, Object> achievementBody(String code, List<String> types, String metric, long target, String period) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Obiettivo " + code);
        body.put("description", "Obiettivo del testbook");
        body.put("actionTypes", types);
        body.put("metric", metric);
        body.put("target", target);
        body.put("period", period);
        return body;
    }

    /** Applica {@code chiave=valore;…}: {@code ~} = null, {@code types=a|b}, {@code code=@DUP} = codice già usato. */
    static void applyOverrides(Map<String, Object> body, String overrides, java.util.function.Supplier<String> duplicate) {
        if (opt(overrides) == null) return;
        for (String kv : overrides.split(";")) {
            String[] p = kv.split("=", 2);
            String v = p.length > 1 ? p[1] : "";
            Object value = switch (p[0]) {
                case "types" -> v.isEmpty() ? List.of() : Arrays.asList(v.split("\\|"));
                case "target", "topN" -> Long.parseLong(v);
                default -> "~".equals(v) ? null : "@DUP".equals(v) ? duplicate.get() : v;
            };
            body.put("types".equals(p[0]) ? "actionTypes" : p[0], value);
        }
    }

    private String createBadge(String code) {
        ok("POST", "/v1/badges", actor("MARKETING"), Map.of("code", code, "name", "Badge " + code, "icon", "star", "color", "#2a78d6"), 201);
        return code;
    }

    private io.loyaltyhub.common.event.LhEvent<JsonNode> effect(String memberId, String badge, String effectId) {
        return event(EFFECT + "badge.award", memberId, Instant.now(),
                json("{\"badgeCode\":\"" + badge + "\",\"effectId\":\"" + effectId + "\",\"campaignCode\":\"CMP-TB\"}"));
    }

    private static List<String> symbols(String id, String types) {
        List<String> out = new ArrayList<>();
        for (String s : types.split(";")) out.add(type(id, s));
        return out;
    }

    private static String type(String id, String symbol) {
        return "tb." + id.substring("TB-GAM-".length()).toLowerCase() + "." + symbol.toLowerCase();
    }

    private static List<JsonNode> byCode(List<JsonNode> facts, String code) {
        return facts.stream().filter(f -> code.equals(f.path("data").path("achievementCode").asString())).toList();
    }

    private static JsonNode find(JsonNode list, String code) {
        for (JsonNode n : list) if (code.equals(n.path("code").asString())) return n;
        throw new AssertionError("assente: " + code);
    }
}
