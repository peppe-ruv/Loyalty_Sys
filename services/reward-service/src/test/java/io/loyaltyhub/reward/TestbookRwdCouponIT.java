package io.loyaltyhub.reward;

import io.loyaltyhub.reward.TestbookRwdCsv.Row;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.domain.CouponCodes;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.loyaltyhub.reward.TestbookRwdCsv.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD — coupon: ciclo di vita × azione × tempo (CPN-001…025), scadenza col job (CPN-040…050), pool,
 * generazione e import (CPN-060…101), ruoli di cassa, annullo e job demo (ROL-030…049), effetto {@code coupon.issue}
 * (EFF). Oracolo: docs/03 §5, reward-service §3–§5, F-CPN-01…03, BO-12, PT-13, Q-52.
 */
class TestbookRwdCouponIT extends TestbookRwdBase {

    private static final Duration VALIDITY = Duration.ofDays(30);

    @Autowired
    CouponService couponService;

    /** Coupon emesso alle {@code issuedAt} da un pool nuovo; {@code rewardCode} di un premio col pool. */
    record Issued(String code, String poolId, String memberId, String rewardCode, Instant expiresAt) {
    }

    Issued issue(Instant issuedAt, int validityDays, int codes) {
        CLOCK.set(issuedAt);
        String poolId = pool(codes, validityDays);
        String rewardCode = reward("DRAFT", Map.of("name", "Buono testbook", "type", "COUPON", "fulfilment", "AUTO_COUPON",
                "couponPoolId", poolId)).path("code").asString();
        String memberId = member("ACTIVE", "GOLD");
        var c = couponService.issue(poolId, memberId, rewardCode, "CAMPAIGN", null, fresh("EFF-TB"), null).orElseThrow();
        return new Issued(c.code(), poolId, memberId, rewardCode, c.expiresAt());
    }

    String rawStatus(String code) {
        return jdbc.sql("SELECT status FROM coupon WHERE code = ?").param(code).query(String.class).single();
    }

    // ---------- CPN: ciclo di vita × azione × tempo ----------

    // TESTBOOK: scelta da decidere, vedi Q-278 (TB-RWD-CPN-002, -008, -011), Q-276 (TB-RWD-CPN-023), Q-284 (TB-RWD-CPN-025)
    @TestFactory
    Stream<DynamicTest> lifecycle() {
        return TestbookRwdCsv.rows("coupon-lifecycle.csv", row -> {
            String state = row.get("state");
            String code;
            Instant expiresAt = null;
            if ("UNKNOWN".equals(state)) {
                code = "NOPE-" + fresh("X").replace("-", "");
            } else {
                Issued i = issue(T0, 30, 2);
                expiresAt = i.expiresAt();
                code = i.code();
                Instant later = T0.plus(Duration.ofDays(1));
                Instant afterExpiry = expiresAt.plus(Duration.ofDays(1));
                switch (state) {
                    case "AVAILABLE" -> {
                        CLOCK.set(later);
                        code = get("/v1/coupon-pools/" + i.poolId() + "/coupons?status=AVAILABLE").path("items").get(0).path("code").asString();
                    }
                    case "ISSUED" -> CLOCK.set(later);
                    case "ISSUED_EXPIRED" -> CLOCK.set(afterExpiry);
                    case "EXPIRED" -> {
                        CLOCK.set(afterExpiry);
                        call("POST", "/v1/demo/jobs/expire-coupons?asOf=" + afterExpiry, "ADMIN:testbook", null);
                        assertThat(rawStatus(code)).isEqualTo("EXPIRED");
                    }
                    case "USED" -> {
                        CLOCK.set(later);
                        call("POST", "/v1/coupons/" + code + "/use", "CARE:testbook.care", null);
                    }
                    case "VOID" -> {
                        CLOCK.set(later);
                        call("POST", "/v1/coupons/" + code + "/void", "CARE:testbook.care", null);
                    }
                    default -> throw new IllegalArgumentException(state);
                }
            }
            String now = row.get("nowFromExpiry");
            if (!"-".equals(now)) {
                CLOCK.set("0".equals(now) ? expiresAt : expiresAt.plus(duration(now)));
            }
            String actor = actorFor(row.get("role"));
            Resp r = switch (row.get("action")) {
                case "USE" -> send("POST", "/v1/coupons/" + code + "/use", actor, null);
                case "USE_LOWER" -> send("POST", "/v1/coupons/" + code.toLowerCase() + "/use", actor, null);
                case "VOID" -> send("POST", "/v1/coupons/" + code + "/void", actor, null);
                default -> send("GET", "/v1/coupons/" + code, null, null);
            };
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (!row.is("expStatusAfter", "-")) {
                assertThat(get("/v1/coupons/" + code).path("status").asString()).as("stato dopo").isEqualTo(row.get("expStatusAfter"));
            }
        });
    }

    // ---------- CPN: scadenza col job ----------

    // TESTBOOK: scelta da decidere, vedi Q-276 (TB-RWD-CPN-041), Q-284 (TB-RWD-CPN-044, -046, -048)
    @TestFactory
    Stream<DynamicTest> expiry() {
        return TestbookRwdCsv.rows("coupon-expiry.csv", row -> {
            Instant expiresAt = Instant.parse(row.get("expiresAt"));
            Issued i = issue(expiresAt.minus(Duration.ofDays(1)), 1, 1);
            assertThat(i.expiresAt()).isEqualTo(expiresAt);
            if (row.is("expStatus", "USED")) {
                CLOCK.set(expiresAt.minus(Duration.ofHours(1)));
                call("POST", "/v1/coupons/" + i.code() + "/use", "CARE:testbook.care", null);
            }
            String asOf = row.get("asOf");
            if (asOf.startsWith("CLOCK")) {
                CLOCK.set(expiresAt.plus(duration(asOf.substring("CLOCK".length()))));
                call("POST", "/v1/demo/jobs/expire-coupons", "ADMIN:testbook", null);
            } else {
                call("POST", "/v1/demo/jobs/expire-coupons?asOf=" + asOf, "ADMIN:testbook", null);
            }
            assertThat(rawStatus(i.code())).isEqualTo(row.get("expStatus"));
        });
    }

    // ---------- CPN: pool ----------

    // TESTBOOK: scelta da decidere, vedi Q-284 (TB-RWD-CPN-061…067, -069…071)
    @TestFactory
    Stream<DynamicTest> createPool() {
        return TestbookRwdCsv.rows("pool-create.csv", row -> {
            CLOCK.set(T0);
            String code = switch (row.get("code")) {
                case "LOWER" -> fresh("pool-tb").toLowerCase();
                case "DUP" -> get("/v1/coupon-pools/" + pool(0, 30)).path("code").asString();
                default -> fresh("POOL-TB");
            };
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            if (!row.is("name", "-")) {
                body.put("name", row.get("name"));
            }
            body.put("prefix", row.get("prefix"));
            if (!row.is("validityDays", "-")) {
                body.put("validityDays", row.integer("validityDays"));
            }
            Resp r = send("POST", "/v1/coupon-pools", actorFor(row.get("role")), body);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (r.status() == 201) {
                JsonNode p = r.body();
                assertThat(p.path("code").asString()).isEqualTo(code.trim().toUpperCase());
                assertThat(p.path("prefix").asString()).isEqualTo(row.get("prefix").trim().toUpperCase());
                assertThat(p.path("validityDays").asInt()).isEqualTo(row.is("validityDays", "-") ? 90 : row.integer("validityDays"));
                assertThat(p.path("total").asLong()).isZero();
                p.path("counts").forEach(n -> assertThat(n.asLong()).isZero());
            }
        });
    }

    // TESTBOOK: scelta da decidere, vedi Q-284 (TB-RWD-CPN-083, -084)
    @TestFactory
    Stream<DynamicTest> generate() {
        return TestbookRwdCsv.rows("pool-generate.csv", row -> {
            CLOCK.set(T0);
            String poolId = pool(0, 30);
            JsonNode pool = get("/v1/coupon-pools/" + poolId);
            String count = row.get("count");
            String actor = actorFor(row.get("role"));
            Resp r = switch (count) {
                case "-" -> send("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of());
                case "UNKNOWN" -> send("POST", "/v1/coupon-pools/" + fresh("NOPE") + "/generate", actor, Map.of("count", 1));
                case "3+2" -> {
                    call("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of("count", 3));
                    yield send("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of("count", 2));
                }
                case "FORMAT100" -> send("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of("count", 100));
                case "SEED5" -> send("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of("count", 5));
                default -> send("POST", "/v1/coupon-pools/" + poolId + "/generate", actor, Map.of("count", Integer.parseInt(count)));
            };
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            long available = get("/v1/coupon-pools/" + poolId).path("counts").path("AVAILABLE").asLong();
            assertThat(available).as("codici AVAILABLE").isEqualTo(row.integer("expAvailable"));
            if (available > 0 && available <= 100) {
                List<String> codes = codes(poolId, "AVAILABLE");
                assertThat(new HashSet<>(codes)).as("codici distinti").hasSize(codes.size());
                String prefix = pool.path("prefix").asString();
                assertThat(codes).allSatisfy(c -> assertThat(c).matches(prefix + "-[A-Z2-9]{4}-[A-Z2-9]{4}"));
                if ("SEED5".equals(count)) {
                    CouponCodes generator = new CouponCodes(prefix, CouponCodes.batchSeed(CouponService.seedFor(pool.path("code").asString()), 0));
                    Set<String> expected = new HashSet<>();
                    for (int k = 0; k < 5; k++) {
                        expected.add(generator.next());
                    }
                    assertThat(new HashSet<>(codes)).isEqualTo(expected);
                }
            }
        });
    }

    List<String> codes(String poolId, String status) {
        List<String> out = new ArrayList<>();
        get("/v1/coupon-pools/" + poolId + "/coupons?size=100&status=" + status).path("items").forEach(c -> out.add(c.path("code").asString()));
        return out;
    }

    // TESTBOOK: scelta da decidere, vedi Q-284 (TB-RWD-CPN-095…098)
    @TestFactory
    Stream<DynamicTest> importCodes() {
        return TestbookRwdCsv.rows("pool-import.csv", row -> {
            CLOCK.set(T0);
            String poolId = pool(0, 30);
            List<String> codes = new ArrayList<>();
            Map<String, String> fresh = new LinkedHashMap<>();
            String tokens = row.get("codes");
            if ("MANY5001".equals(tokens)) {
                for (int k = 0; k < 5001; k++) {
                    codes.add("IMP" + k + "-" + fresh("M").replace("-", ""));
                }
            } else if (!"EMPTY".equals(tokens)) {
                for (String t : tokens.split("\\|")) {
                    codes.add(switch (t) {
                        case "EXIST" -> codes(pool(1, 30), "AVAILABLE").get(0);
                        case "LOWER" -> "  imp-" + fresh("l").toLowerCase().replace("-", "") + " ";
                        default -> t.startsWith("NEW") ? fresh.computeIfAbsent(t, x -> "IMP-" + fresh("N").replace("-", "")) : t;
                    });
                }
            }
            Resp r = send("POST", "/v1/coupon-pools/" + poolId + "/import", actorFor(row.get("role")), Map.of("codes", codes));
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            long available = get("/v1/coupon-pools/" + poolId).path("counts").path("AVAILABLE").asLong();
            if (r.status() == 200) {
                assertThat(r.body().path("imported").asInt()).isEqualTo(row.integer("expImported"));
                assertThat(r.body().path("skipped").size()).isEqualTo(row.integer("expSkipped"));
                assertThat(available).isEqualTo(row.integer("expImported"));
                if ("LOWER".equals(tokens)) {
                    assertThat(codes(poolId, "AVAILABLE")).containsExactly(codes.get(0).trim().toUpperCase());
                }
            } else {
                assertThat(available).as("nulla importato").isZero();
            }
        });
    }

    // ---------- ROL: cassa, annullo, job demo ----------

    @TestFactory
    Stream<DynamicTest> roles() {
        return TestbookRwdCsv.rows("roles-coupon.csv", row -> {
            String action = row.get("action");
            String actor = actorFor(row.get("role"));
            int expected = row.integer("expHttp");
            if (action.startsWith("JOB")) {
                CLOCK.set(T0);
                String path = "JOB_TIMEOUT".equals(action) ? "timeout-redemptions" : "expire-coupons";
                // asOf remoto: nessun effetto sugli altri casi
                assertThat(send("POST", "/v1/demo/jobs/" + path + "?asOf=2000-01-01T00:00:00Z", actor, null).status()).isEqualTo(expected);
                return;
            }
            Issued i = issue(T0, 30, 1);
            CLOCK.set(T0.plus(Duration.ofDays(1)));
            Resp r = send("POST", "/v1/coupons/" + i.code() + ("USE".equals(action) ? "/use" : "/void"), actor, null);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(expected);
            String after = expected == 403 ? "ISSUED" : "USE".equals(action) ? "USED" : "VOID";
            assertThat(rawStatus(i.code())).isEqualTo(after);
        });
    }

    // ---------- scenari: fatti, portale, pool, effetto coupon.issue ----------

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return Stream.of(
                scenario("TB-RWD-CPN-030", "uso alla cassa: fatto coupon.used", this::usedFact),
                scenario("TB-RWD-CPN-031", "coupon del portale senza memberId", () ->
                        assertThat(send("GET", "/v1/portal/coupons", null, null).status()).isEqualTo(400)),
                scenario("TB-RWD-CPN-032", "coupon del portale: attivo e scaduto", this::portalCoupons),
                scenario("TB-RWD-CPN-100", "codici del pool filtrati per stato e membro", this::poolListing),
                scenario("TB-RWD-CPN-101", "conteggi del pool per stato", this::poolCounts),
                scenario("TB-RWD-EFF-001", "coupon.issue valido", this::effectIssues),
                scenario("TB-RWD-EFF-002", "stesso effectId in un nuovo messaggio", this::effectSameEffectId),
                scenario("TB-RWD-EFF-003", "stesso evento riconsegnato", this::effectRedelivered),
                scenario("TB-RWD-EFF-004", "pool vuoto → DLQ COUPON_POOL_EMPTY", () -> effectToDlq("EMPTY", "COUPON_POOL_EMPTY")),
                // TESTBOOK: scelta da decidere, vedi Q-285 (TB-RWD-EFF-005…008, rami senza specifica dell'effetto coupon.issue)
                scenario("TB-RWD-EFF-005", "premio inesistente → DLQ", () -> effectToDlq("UNKNOWN", "REWARD_NOT_FOUND")),
                scenario("TB-RWD-EFF-006", "premio senza pool → DLQ", () -> effectToDlq("NOPOOL", "COUPON_POOL_MISSING")),
                scenario("TB-RWD-EFF-007", "effetto senza membro → DLQ", () -> effectToDlq("NOSUBJECT", "INVALID_EFFECT")),
                scenario("TB-RWD-EFF-008", "effectId assente", this::effectWithoutEffectId),
                scenario("TB-RWD-EFF-009", "premio DRAFT con pool", this::effectOnDraftReward),
                scenario("TB-RWD-AUD-009", "audit di uso e annullo dei codici", this::auditCoupons));
    }

    void auditCoupons() {
        Issued used = issue(T0, 30, 2);
        String voided = couponService.issue(used.poolId(), used.memberId(), used.rewardCode(), "CAMPAIGN", null, null, null).orElseThrow().code();
        call("POST", "/v1/coupons/" + used.code() + "/use", "LEGAL:giulia.legal", null);
        call("POST", "/v1/coupons/" + voided + "/void", "CARE:anna.care", null);
        List<JsonNode> u = audits("COUPON", used.code());
        List<JsonNode> v = audits("COUPON", voided);
        assertThat(u).hasSize(1);
        assertThat(u.get(0).path("lhactor").asString()).isEqualTo("LEGAL:giulia.legal");
        assertThat(v).hasSize(1);
        assertThat(v.get(0).path("lhactor").asString()).isEqualTo("CARE:anna.care");
        assertThat(v.get(0).path("data").path("after").path("status").asString()).isEqualTo("VOID");
    }

    void usedFact() {
        Issued i = issue(T0, 30, 1);
        CLOCK.set(T0.plus(Duration.ofDays(1)));
        call("POST", "/v1/coupons/" + i.code() + "/use", "CARE:testbook.care", null);
        JsonNode fact = tap().await("lh.facts.v1", r -> "io.loyaltyhub.fact.coupon.used".equals(r.event().path("type").asString())
                && i.code().equals(r.event().path("data").path("couponCode").asString())).event();
        assertThat(fact.path("data").path("rewardCode").asString()).isEqualTo(i.rewardCode());
        assertThat(fact.path("lhactor").asString()).isEqualTo("CARE:testbook.care");
    }

    void portalCoupons() {
        Issued old = issue(T0.minus(Duration.ofDays(40)), 30, 1);
        CLOCK.set(T0);
        String poolId = pool(1, 30);
        String active = couponService.issue(poolId, old.memberId(), old.rewardCode(), "REDEMPTION", null, null, null).orElseThrow().code();
        CLOCK.set(T0.plus(Duration.ofDays(1)));
        JsonNode list = get("/v1/portal/coupons?memberId=" + old.memberId());
        assertThat(list.size()).isEqualTo(2);
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        list.forEach(c -> byCode.put(c.path("code").asString(), c));
        assertThat(byCode.get(active).path("status").asString()).isEqualTo("ISSUED");
        assertThat(byCode.get(old.code()).path("status").asString()).isEqualTo("EXPIRED");
        for (JsonNode c : list) {
            assertThat(c.path("rewardName").asString()).isEqualTo("Buono testbook");
            assertThat(c.has("issuedAt") && c.has("expiresAt") && c.has("origin")).isTrue();
        }
    }

    void poolListing() {
        Issued i = issue(T0, 30, 3);
        JsonNode issued = get("/v1/coupon-pools/" + i.poolId() + "/coupons?status=ISSUED");
        assertThat(issued.path("items").size()).isEqualTo(1);
        assertThat(issued.path("items").get(0).path("code").asString()).isEqualTo(i.code());
        assertThat(issued.path("page").path("totalItems").asLong()).isEqualTo(1);
        assertThat(get("/v1/coupon-pools/" + i.poolId() + "/coupons?memberId=" + i.memberId()).path("items").size()).isEqualTo(1);
        assertThat(get("/v1/coupon-pools/" + i.poolId() + "/coupons?status=AVAILABLE").path("items").size()).isEqualTo(2);
    }

    void poolCounts() {
        Issued i = issue(T0, 30, 4);
        String used = couponService.issue(i.poolId(), i.memberId(), i.rewardCode(), "CAMPAIGN", null, null, null).orElseThrow().code();
        String voided = couponService.issue(i.poolId(), i.memberId(), i.rewardCode(), "CAMPAIGN", null, null, null).orElseThrow().code();
        call("POST", "/v1/coupons/" + used + "/use", "CARE:testbook.care", null);
        call("POST", "/v1/coupons/" + voided + "/void", "CARE:testbook.care", null);
        JsonNode p = get("/v1/coupon-pools/" + i.poolId());
        JsonNode counts = p.path("counts");
        assertThat(counts.path("AVAILABLE").asLong()).isEqualTo(1);
        assertThat(counts.path("ISSUED").asLong()).isEqualTo(1);
        assertThat(counts.path("USED").asLong()).isEqualTo(1);
        assertThat(counts.path("VOID").asLong()).isEqualTo(1);
        assertThat(counts.path("EXPIRED").asLong()).isZero();
        assertThat(p.path("total").asLong()).isEqualTo(4);
    }

    // ---------- effetto coupon.issue ----------

    record Setup(String memberId, String rewardCode, String poolId) {
    }

    Setup effectSetup(int codes) {
        CLOCK.set(T0);
        String poolId = pool(codes, 30);
        String rewardCode = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId)).path("code").asString();
        return new Setup(member("ACTIVE", "GOLD"), rewardCode, poolId);
    }

    Map<String, Object> effectData(String effectId, String rewardCode) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (effectId != null) {
            d.put("effectId", effectId);
        }
        d.put("campaignCode", "CMP-TB-COUPON");
        d.put("actionId", fresh("ACT"));
        d.put("rewardCode", rewardCode);
        return d;
    }

    JsonNode memberCoupons(String memberId) {
        return get("/v1/portal/coupons?memberId=" + memberId);
    }

    void effectIssues() {
        Setup s = effectSetup(2);
        String effectId = fresh("EFF");
        String eventId = publishCouponIssue(fresh("01TBEFF"), "member:" + s.memberId(), effectData(effectId, s.rewardCode()));
        awaitProcessed(eventId);
        JsonNode list = memberCoupons(s.memberId());
        assertThat(list.size()).isEqualTo(1);
        JsonNode c = list.get(0);
        assertThat(c.path("status").asString()).isEqualTo("ISSUED");
        assertThat(c.path("origin").asString()).isEqualTo("CAMPAIGN");
        String code = c.path("code").asString();
        assertThat(jdbc.sql("SELECT effect_id FROM coupon WHERE code = ?").param(code).query(String.class).single()).isEqualTo(effectId);
        JsonNode fact = tap().await("lh.facts.v1", r -> "io.loyaltyhub.fact.coupon.issued".equals(r.event().path("type").asString())
                && code.equals(r.event().path("data").path("couponCode").asString())).event();
        assertThat(fact.path("lhcausationid").asString()).isEqualTo(eventId);
        assertThat(fact.path("data").path("origin").asString()).isEqualTo("CAMPAIGN");
    }

    void effectSameEffectId() {
        Setup s = effectSetup(2);
        String effectId = fresh("EFF");
        awaitProcessed(publishCouponIssue(fresh("01TBEFF"), "member:" + s.memberId(), effectData(effectId, s.rewardCode())));
        awaitProcessed(publishCouponIssue(fresh("01TBEFF"), "member:" + s.memberId(), effectData(effectId, s.rewardCode())));
        assertThat(memberCoupons(s.memberId()).size()).isEqualTo(1);
    }

    void effectRedelivered() {
        Setup s = effectSetup(2);
        String eventId = fresh("01TBEFF");
        Map<String, Object> data = effectData(fresh("EFF"), s.rewardCode());
        publishCouponIssue(eventId, "member:" + s.memberId(), data);
        publishCouponIssue(eventId, "member:" + s.memberId(), data);
        // marcatore sulla stessa partizione, elaborato dopo i due invii
        awaitProcessed(publishCouponIssue(fresh("01TBMARK"), "member:" + member("ACTIVE", "GOLD"),
                effectData(fresh("EFF"), s.rewardCode())));
        assertThat(memberCoupons(s.memberId()).size()).isEqualTo(1);
    }

    void effectToDlq(String kind, String errorCode) {
        Setup s = effectSetup("EMPTY".equals(kind) ? 0 : 1);
        String rewardCode = switch (kind) {
            case "UNKNOWN" -> fresh("RWD-NOPE");
            case "NOPOOL" -> reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON")).path("code").asString();
            default -> s.rewardCode();
        };
        String effectId = fresh("EFF");
        String eventId = fresh("01TBDLQ");
        publishCouponIssue(eventId, "NOSUBJECT".equals(kind) ? null : "member:" + s.memberId(), effectData(effectId, rewardCode));
        Rec dlq = tap().await("lh.dlq.v1", r -> r.event().toString().contains(effectId));
        assertThat(dlq.headers().get("lh-error-code")).isEqualTo(errorCode);
        // una sola copia: errore non ritentabile
        drainOutbox();
        assertThat(tap().matching("lh.dlq.v1", r -> r.event().toString().contains(effectId))).hasSize(1);
        assertThat(memberCoupons(s.memberId()).size()).isZero();
    }

    void effectWithoutEffectId() {
        Setup s = effectSetup(1);
        String eventId = fresh("01TBEFF");
        awaitProcessed(publishCouponIssue(eventId, "member:" + s.memberId(), effectData(null, s.rewardCode())));
        JsonNode list = memberCoupons(s.memberId());
        assertThat(list.size()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT effect_id FROM coupon WHERE code = ?").param(list.get(0).path("code").asString())
                .query(String.class).single()).isEqualTo(eventId);
    }

    void effectOnDraftReward() {
        CLOCK.set(T0);
        String poolId = pool(1, 30);
        String rewardCode = reward("DRAFT", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId)).path("code").asString();
        String memberId = member("ACTIVE", "GOLD");
        awaitProcessed(publishCouponIssue(fresh("01TBEFF"), "member:" + memberId, effectData(fresh("EFF"), rewardCode)));
        assertThat(memberCoupons(memberId).size()).isEqualTo(1);
    }
}
