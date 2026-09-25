package io.loyaltyhub.gamification;

import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.gamification.messaging.MemberSnapshotHandler;
import io.loyaltyhub.gamification.messaging.PlaysGrantHandler;
import io.loyaltyhub.gamification.messaging.EffectsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testbook TB-GAM — crediti di gioco da effetto {@code plays.grant} ({@code GRT}) e snapshot del membro dai fatti di
 * member ({@code SNP}) (docs/testbook/TB-GAM-gioco.md §18–§19). Oracolo: F-IW-05, docs/03 §2, §3.4, §6, §8,
 * gamification §4, campaign-service §5 (errore a valle in DLQ), contratto {@code contest.plays.granted}, RNF-03.
 */
class TestbookGamEffectIT extends TestbookGamBase {

    @Autowired
    private PlaysGrantHandler playsGrantHandler;
    @Autowired
    private MemberSnapshotHandler memberSnapshotHandler;
    @Autowired
    private EffectsListener effectsListener;

    // ---------- plays.grant ----------

    @Test
    @DisplayName("[TB-GAM-GRT-001] plays.grant count 2: due crediti e fatto contest.plays.granted")
    void grantTwo() {
        String code = liveContest("TB-GAM-GRT-001");
        String m = member("ACTIVE");
        playsGrantHandler.handle(grant(m, code, 2, "EFF-GRT-001"));
        assertThat(credits(m, code)).isEqualTo(2);
        List<JsonNode> granted = facts("contest.plays.granted", m);
        assertThat(granted).hasSize(1);
        JsonNode d = granted.getFirst().path("data");
        assertThat(d.path("contestCode").asString()).isEqualTo(code);
        assertThat(d.path("count").asInt()).isEqualTo(2);
        assertThat(d.path("effectId").asString()).isEqualTo("EFF-GRT-001");
    }

    @Test
    @DisplayName("[TB-GAM-GRT-002] stesso effectId consegnato due volte: crediti e fatto una volta sola")
    void grantIdempotent() {
        String code = liveContest("TB-GAM-GRT-002");
        String m = member("ACTIVE");
        playsGrantHandler.handle(grant(m, code, 3, "EFF-GRT-002"));
        playsGrantHandler.handle(grant(m, code, 3, "EFF-GRT-002"));
        assertThat(credits(m, code)).isEqualTo(3);
        assertThat(facts("contest.plays.granted", m)).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-GRT-003] due effetti diversi: i crediti si sommano")
    void grantsSum() {
        String code = liveContest("TB-GAM-GRT-003");
        String m = member("ACTIVE");
        playsGrantHandler.handle(grant(m, code, 3, "EFF-GRT-003-A"));
        playsGrantHandler.handle(grant(m, code, 2, "EFF-GRT-003-B"));
        assertThat(credits(m, code)).isEqualTo(5);
    }

    // Q-297 DECISA: count assente → 1 credito (come Q-230)
    @Test
    @DisplayName("[TB-GAM-GRT-004] count assente: un credito")
    void grantWithoutCount() {
        String code = liveContest("TB-GAM-GRT-004");
        String m = member("ACTIVE");
        playsGrantHandler.handle(event(EFFECT + "plays.grant", m, Instant.now(),
                json("{\"contestCode\":\"" + code + "\",\"effectId\":\"EFF-GRT-004\"}")));
        assertThat(credits(m, code)).isEqualTo(1);
    }

    // Q-297 DECISA: count < 1 → DLQ INVALID_EFFECT, nessun credito
    @Test
    @DisplayName("[TB-GAM-GRT-005] count 0 o negativo: errore non ritentabile INVALID_EFFECT, nessun credito")
    void grantZero() {
        String code = liveContest("TB-GAM-GRT-005");
        String m = member("ACTIVE");
        for (int count : new int[] {0, -2}) {
            assertThatThrownBy(() -> playsGrantHandler.handle(grant(m, code, count, "EFF-GRT-005-" + Math.abs(count))))
                    .isInstanceOf(NonRetryableEventException.class)
                    .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("INVALID_EFFECT"));
        }
        assertThat(credits(m, code)).isZero();
        assertThat(count("SELECT count(*) FROM play_grant WHERE effect_id LIKE 'EFF-GRT-005%'")).isZero();
    }

    @Test
    @DisplayName("[TB-GAM-GRT-006] concorso inesistente: errore non ritentabile CONTEST_NOT_FOUND (DLQ)")
    void grantUnknownContest() {
        String m = member("ACTIVE");
        assertThatThrownBy(() -> playsGrantHandler.handle(grant(m, "IW-NON-ESISTE", 1, "EFF-GRT-006")))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("CONTEST_NOT_FOUND"));
        assertThat(count("SELECT count(*) FROM play_grant WHERE effect_id = 'EFF-GRT-006'")).isZero();
    }

    // Q-297 DECISA: effetto senza dati → DLQ INVALID_EFFECT
    @Test
    @DisplayName("[TB-GAM-GRT-007] effetto senza dati: errore non ritentabile INVALID_EFFECT")
    void grantWithoutData() {
        assertThatThrownBy(() -> playsGrantHandler.handle(event(EFFECT + "plays.grant", member("ACTIVE"), Instant.now(), null)))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("INVALID_EFFECT"));
    }

    // Q-297 DECISA: effetto senza membro → DLQ INVALID_EFFECT
    @Test
    @DisplayName("[TB-GAM-GRT-008] effetto senza membro nel subject: errore non ritentabile INVALID_EFFECT")
    void grantWithoutMember() {
        String code = liveContest("TB-GAM-GRT-008");
        assertThatThrownBy(() -> playsGrantHandler.handle(event(EFFECT + "plays.grant", null, Instant.now(),
                json("{\"contestCode\":\"" + code + "\",\"count\":1}"))))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("INVALID_EFFECT"));
    }

    // Q-297 DECISA: il credito verso un concorso DRAFT resta e vale quando il concorso va LIVE
    @Test
    @DisplayName("[TB-GAM-GRT-009] crediti su un concorso DRAFT: conservati e usabili quando va LIVE")
    void grantBeforeLive() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-GRT-009");
        Map<String, Object> o = Map.of("freePlayDaily", false);
        String contest = contestIn(code, "DRAFT", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), o);
        String m = member("ACTIVE");
        playsGrantHandler.handle(grant(m, code, 1, "EFF-GRT-009"));
        setStatus(contest, "LIVE");
        Resp r = play(code, m);
        assertThat(r.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT kind FROM play WHERE id = ?").param(r.body().path("playId").asString()).query(String.class).single())
                .isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("[TB-GAM-GRT-010] plays.grant dal listener di lh.effects.v1, consegnato due volte: 2 crediti e un fatto")
    void grantThroughListener() {
        String code = liveContest("TB-GAM-GRT-010");
        String m = member("ACTIVE");
        var e = grant(m, code, 2, "EFF-GRT-010");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lh.effects.v1", 0, 0L, m, mapper.writeValueAsString(e));
        effectsListener.onEffect(record, () -> { });
        effectsListener.onEffect(record, () -> { });
        assertThat(credits(m, code)).isEqualTo(2);
        assertThat(facts("contest.plays.granted", m)).hasSize(1);
    }

    @Test
    @DisplayName("[TB-GAM-GRT-011] contestCode con l'id del concorso: CONTEST_NOT_FOUND")
    void grantWithId() {
        CLOCK.set(T0);
        String contest = contestIn(contestCode("TB-GAM-GRT-011"), "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        assertThatThrownBy(() -> playsGrantHandler.handle(grant(member("ACTIVE"), contest, 1, "EFF-GRT-011")))
                .isInstanceOf(NonRetryableEventException.class)
                .satisfies(e -> assertThat(((NonRetryableEventException) e).code()).isEqualTo("CONTEST_NOT_FOUND"));
    }

    // ---------- snapshot del membro ----------

    @Test
    @DisplayName("[TB-GAM-SNP-001] member.registered ACTIVE: il membro può giocare")
    void registeredCanPlay() {
        String code = liveContest("TB-GAM-SNP-001");
        String m = newMemberId();
        memberSnapshotHandler.handle(event(FACT + "member.registered", m, Instant.now(),
                json("{\"memberId\":\"" + m + "\",\"status\":\"ACTIVE\",\"nickname\":\"Nuovo\"}")));
        CLOCK.set(T0);
        assertThat(play(code, m).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-GAM-SNP-002] member.status.changed a BLOCKED: 422 MEMBER_NOT_ACTIVE")
    void blockedCannotPlay() {
        String code = liveContest("TB-GAM-SNP-002");
        String m = member("ACTIVE");
        memberSnapshotHandler.handle(statusChanged(m, "ACTIVE", "BLOCKED"));
        CLOCK.set(T0);
        Resp r = play(code, m);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    @Test
    @DisplayName("[TB-GAM-SNP-003] di nuovo ACTIVE dopo il blocco: può giocare")
    void unblockedCanPlay() {
        String code = liveContest("TB-GAM-SNP-003");
        String m = member("ACTIVE");
        memberSnapshotHandler.handle(statusChanged(m, "ACTIVE", "BLOCKED"));
        memberSnapshotHandler.handle(statusChanged(m, "BLOCKED", "ACTIVE"));
        CLOCK.set(T0);
        assertThat(play(code, m).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("[TB-GAM-SNP-004] registrato senza nickname: nome e iniziale del cognome")
    void defaultNickname() {
        String m = newMemberId();
        memberSnapshotHandler.handle(event(FACT + "member.registered", m, Instant.now(),
                json("{\"memberId\":\"" + m + "\",\"status\":\"ACTIVE\",\"firstName\":\"Giulia\",\"lastName\":\"Rossi\"}")));
        assertThat(jdbc.sql("SELECT nickname FROM gamification_member_snapshot WHERE member_id = ?").param(m)
                .query(String.class).single()).isEqualTo("Giulia R.");
    }

    @Test
    @DisplayName("[TB-GAM-SNP-005] member.registered con stato BLOCKED: 422 MEMBER_NOT_ACTIVE")
    void registeredBlocked() {
        String code = liveContest("TB-GAM-SNP-005");
        String m = newMemberId();
        memberSnapshotHandler.handle(event(FACT + "member.registered", m, Instant.now(),
                json("{\"memberId\":\"" + m + "\",\"status\":\"BLOCKED\",\"nickname\":\"Bloccato\"}")));
        CLOCK.set(T0);
        assertThat(play(code, m).code()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    // ---------- supporto ----------

    private String liveContest(String rowId) {
        CLOCK.set(T0);
        String code = contestCode(rowId);
        contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofHours(5)), null);
        CLOCK.reset();
        return code;
    }

    private io.loyaltyhub.common.event.LhEvent<JsonNode> grant(String memberId, String contestCode, int count, String effectId) {
        return event(EFFECT + "plays.grant", memberId, Instant.now(), json("{\"contestCode\":\"" + contestCode + "\",\"count\":" + count
                + ",\"effectId\":\"" + effectId + "\",\"campaignCode\":\"CMP-SURVEY\"}"));
    }

    private io.loyaltyhub.common.event.LhEvent<JsonNode> statusChanged(String memberId, String from, String to) {
        return event(FACT + "member.status.changed", memberId, Instant.now(),
                json("{\"memberId\":\"" + memberId + "\",\"previousStatus\":\"" + from + "\",\"newStatus\":\"" + to + "\"}"));
    }

    private int credits(String memberId, String code) {
        CLOCK.set(T0);
        JsonNode c = portalContest(memberId, code);
        CLOCK.reset();
        assertThat(c).isNotNull();
        return c.path("credits").asInt();
    }

    private static String newMemberId() {
        return String.format("MBR-7%05d", next());
    }
}
