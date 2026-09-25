package io.loyaltyhub.gamification;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestbookGamPlayIT extends PlayIT {

    @Test
    @DisplayName("[TB-GAM-PLY-001] Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=false, credits=0, limits=null")
    void testPlayFreeSuccess() {
        String code = "IW-TB-PLY-001";
        createLiveTb(code, 5, true, null);
        String memberId = "MBR-000001"; // active member
        JsonNode result = playTb(code, memberId, 200);
        assertThat(result.path("outcome").asText()).isIn("WIN", "LOSE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-002] Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=true, credits=1, limits=null")
    void testPlayCreditSuccess() throws Exception {
        String code = "IW-TB-PLY-002";
        createLiveTb(code, 5, true, null);
        String memberId = "MBR-000002";
        playTb(code, memberId, 200); // consume free play
        publishGrantTb(memberId, code, 1, "EFF-TB-PLY-002");
        awaitCreditsTb(memberId, code, 1);
        JsonNode result = playTb(code, memberId, 200);
        assertThat(result.path("outcome").asText()).isIn("WIN", "LOSE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-003] Status LIVE, in period, ACTIVE, freePlay=false, freeUsed=false, credits=0, limits=null")
    void testPlayNoCredits() {
        String code = "IW-TB-PLY-003";
        createLiveTb(code, 5, false, null);
        String memberId = "MBR-000003";
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("NO_PLAYS_AVAILABLE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-004] Status DRAFT, in period, ACTIVE, credits=1")
    void testPlayNotLive() {
        String code = "IW-TB-PLY-004";
        // Create as DRAFT
        createDraftTb(code);
        String memberId = "MBR-000004";
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("CONTEST_NOT_LIVE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-005] Status LIVE, before start, ACTIVE, credits=1")
    void testPlayBeforeStart() {
        String code = "IW-TB-PLY-005";
        String id = createLiveTb(code, 5, true, null);
        // Change start time to future
        org.springframework.jdbc.core.simple.JdbcClient jdbcTb = getJdbcTb();
        jdbcTb.sql("UPDATE contest SET start_at = ? WHERE id = ?").params(Timestamp.from(Instant.now().plusSeconds(10000)), id).update();
        String memberId = "MBR-000005";
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("CONTEST_NOT_LIVE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-006] Status LIVE, after end, ACTIVE, credits=1")
    void testPlayAfterEnd() {
        String code = "IW-TB-PLY-006";
        String id = createLiveTb(code, 5, true, null);
        org.springframework.jdbc.core.simple.JdbcClient jdbcTb = getJdbcTb();
        jdbcTb.sql("UPDATE contest SET end_at = ? WHERE id = ?").params(Timestamp.from(Instant.now().minusSeconds(10000)), id).update();
        String memberId = "MBR-000006";
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("CONTEST_NOT_LIVE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-007] Status LIVE, in period, INACTIVE, credits=1")
    void testPlayMemberInactive() {
        String code = "IW-TB-PLY-007";
        createLiveTb(code, 5, true, null);
        String memberId = "MBR-000008"; // inactive member in seed
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-008] Status LIVE, in period, ACTIVE, freePlay=true, freeUsed=false, dailyLimit=reached")
    void testPlayDailyLimit() throws Exception {
        String code = "IW-TB-PLY-008";
        createLiveTb(code, 5, true, 1);
        String memberId = "MBR-000001";
        playTb(code, memberId, 200); // consumed 1 daily play
        publishGrantTb(memberId, code, 1, "EFF-TB-PLY-008");
        awaitCreditsTb(memberId, code, 1);
        JsonNode result = playTb(code, memberId, 422);
        assertThat(result.path("code").asText()).isEqualTo("DAILY_LIMIT_REACHED");
    }

    @Test
    @DisplayName("[TB-GAM-PLY-009] Status LIVE, in period, ACTIVE, credits=1, winsLimit=reached")
    void testPlayWinsLimit() throws Exception {
        String code = "IW-TB-PLY-009";
        String id = createLiveTb(code, 5, true, null);
        org.springframework.jdbc.core.simple.JdbcClient jdbcTb = getJdbcTb();
        jdbcTb.sql("UPDATE contest SET max_wins_per_member = 0 WHERE id = ?").params(id).update();
        // plant instant
        jdbcTb.sql("UPDATE winning_instant SET instant_at = ? WHERE contest_id = ?").params(Timestamp.from(Instant.now().minusSeconds(1)), id).update();
        String memberId = "MBR-000002";
        JsonNode result = playTb(code, memberId, 200);
        assertThat(result.path("outcome").asText()).isEqualTo("LOSE"); // blocked by cap
    }

    @Test
    @DisplayName("[TB-GAM-PLY-010] Giocata concorrente con 1 istante scaduto, 50 thread")
    void testPlayConcurrency() throws Exception {
        fiftyConcurrentPlaysOneExpiredInstantOneWin();
    }

    @Test
    @DisplayName("[TB-GAM-INST-004] Planted instant (Q-62) e claim")
    void testPlantAndClaim() throws Exception {
        String id = createLiveTb("IW-TB-INST-004", 5, true, null);
        String code = "IW-TB-INST-004";
        // Call plant
        int actualPort = 8086;
        try {
            actualPort = (int) org.springframework.test.util.ReflectionTestUtils.getField(this, "port");
        } catch (Exception e) {}
        var spec = org.springframework.web.client.RestClient.create("http://localhost:" + actualPort)
                .post().uri("/v1/demo/contests/" + id + "/plant-instant").header("X-LH-Actor", "ADMIN:test")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("prizeCode", "PTS-10"));
        spec.retrieve().toBodilessEntity();

        String memberId = "MBR-000003";
        JsonNode result = playTb(code, memberId, 200);
        assertThat(result.path("outcome").asText()).isEqualTo("WIN");
    }

    // helper

    private org.springframework.jdbc.core.simple.JdbcClient getJdbcTb() {
        try {
            return (org.springframework.jdbc.core.simple.JdbcClient) org.springframework.test.util.ReflectionTestUtils.getField(this, "jdbc");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String createDraftTb(String code) {
        String id = java.util.UUID.randomUUID().toString();
        Instant start = Instant.now().minus(java.time.Duration.ofDays(1));
        org.springframework.jdbc.core.simple.JdbcClient jdbcTb = getJdbcTb();
        jdbcTb.sql("INSERT INTO contest (id, code, name, mechanic, start_at, end_at, free_play_daily, distribution, seed, status, created_by, version) " +
                 "VALUES (?, ?, 'Draft Contest', 'WHEEL', ?, ?, true, 'UNIFORM', 42, 'DRAFT', 'test', 0)")
                .params(id, code, Timestamp.from(start), Timestamp.from(start.plus(java.time.Duration.ofDays(10)))).update();
        return id;
    }

    private String createLiveTb(String code, int qty, boolean free, Integer cap) {
        try {
            java.lang.reflect.Method m = PlayIT.class.getDeclaredMethod("createLive", String.class, int.class, boolean.class, Integer.class);
            m.setAccessible(true);
            return (String) m.invoke(this, code, qty, free, cap);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode playTb(String code, String memberId, int expected) {
        try {
            java.lang.reflect.Method m = PlayIT.class.getDeclaredMethod("play", String.class, String.class, int.class);
            m.setAccessible(true);
            return (JsonNode) m.invoke(this, code, memberId, expected);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void publishGrantTb(String memberId, String contestCode, int count, String effectId) throws Exception {
        java.lang.reflect.Method m = PlayIT.class.getDeclaredMethod("publishGrant", String.class, String.class, int.class, String.class);
        m.setAccessible(true);
        m.invoke(this, memberId, contestCode, count, effectId);
    }

    private JsonNode awaitCreditsTb(String memberId, String code, int atLeast) throws Exception {
        java.lang.reflect.Method m = PlayIT.class.getDeclaredMethod("awaitCredits", String.class, String.class, int.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(this, memberId, code, atLeast);
    }
}
