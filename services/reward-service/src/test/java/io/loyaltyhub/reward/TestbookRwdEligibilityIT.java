package io.loyaltyhub.reward;

import io.loyaltyhub.reward.TestbookRwdCsv.Row;
import io.loyaltyhub.reward.domain.Redemption;
import io.loyaltyhub.reward.domain.RedemptionStatus;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static io.loyaltyhub.reward.TestbookRwdCsv.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD — visibilità ed eleggibilità (VIS), precedenza dei controlli della richiesta (ORD), snapshot del
 * membro dai fatti (SNP), ultimo pezzo (STK-020/021). Oracolo: reward-service §3 e §5, docs/03 §5, PT-03/PT-04.
 */
class TestbookRwdEligibilityIT extends TestbookRwdBase {

    @Autowired
    RedemptionRepository redemptions;

    // ---------- VIS / ORD ----------

    // TESTBOOK: ambiguo, vedi TB-RWD-VIS-043, TB-RWD-VIS-046, TB-RWD-VIS-058
    @TestFactory
    Stream<DynamicTest> visibility() {
        return TestbookRwdCsv.rows("visibility.csv", this::eligibility);
    }

    /** Coppie di condizioni vere: la specifica ammette uno dei due codici; il codice esatto è quello di oggi. */
    // TESTBOOK: ambiguo, vedi TB-RWD-ORD-001…015 (precedenza dei controlli non fissata dalle specifiche)
    @TestFactory
    Stream<DynamicTest> order() {
        return TestbookRwdCsv.rows("order.csv", this::eligibility);
    }

    private void eligibility(Row a) {
        Instant now = a.is("now", "-") ? T0 : Instant.parse(a.get("now"));
        CLOCK.set(now);
        String memberStatus = a.get("memberStatus");
        String memberId = member("NONE".equals(memberStatus) ? null : memberStatus, a.get("memberTier"),
                split(a.get("memberSegments")).toArray(String[]::new));
        String type = a.get("type");
        String stockTotal = a.get("stockTotal");

        String code;
        String rewardId = null;
        if (a.is("rewardCode", "UNKNOWN")) {
            code = fresh("RWD-NOPE");
        } else {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", type);
            f.put("fulfilment", "DIGITAL".equals(type) ? "INSTANT" : "MANUAL");
            f.put("validFrom", instant(a.get("validFrom"), now));
            f.put("validTo", instant(a.get("validTo"), now));
            f.put("eligibleTiers", split(a.get("rewardTiers")));
            f.put("eligibleSegments", split(a.get("rewardSegments")));
            f.put("stockTotal", "-".equals(stockTotal) ? null : Integer.parseInt(stockTotal));
            f.put("perMemberLimit", a.is("perMemberLimit", "-") ? null : a.integer("perMemberLimit"));
            JsonNode rw = reward(a.get("status"), f);
            code = rw.path("code").asString();
            rewardId = rw.path("id").asString();
            if (a.is("rewardCode", "LOW9")) {
                // dato di prova: 91 pezzi già prenotati
                jdbc.sql("UPDATE reward SET stock_remaining = 9 WHERE id = ?").param(rewardId).update();
            }
            seedPrior(memberId, code, a.integer("priorActive"), a.integer("priorClosed"));
        }
        int redemptionsBefore = memberRedemptions(memberId);

        // catalogo portale (PT-03) e dettaglio (PT-04)
        JsonNode entry = catalogEntry(memberId, code);
        Resp detail = send("GET", "/v1/portal/rewards/" + code + "?memberId=" + memberId, null, null);
        if (a.is("expVisible", "Y")) {
            assertThat(entry).as("premio nel catalogo").isNotNull();
            String locked = a.get("expLocked");
            if ("-".equals(locked)) {
                assertThat(entry.path("lockedByTier").isMissingNode() || entry.path("lockedByTier").isNull())
                        .as("senza lucchetto: " + entry).isTrue();
            } else {
                assertThat(texts(entry.path("lockedByTier").path("requiredTiers"))).containsExactlyElementsOf(split(locked));
            }
            assertThat(entry.path("stockState").asString()).isEqualTo(a.get("expStockState"));
            assertThat(entry.path("perMemberLimitReached").asBoolean()).isEqualTo(a.is("expLimitReached", "Y"));
            assertThat(detail.status()).as("dettaglio " + detail.body()).isEqualTo(200);
        } else {
            assertThat(entry).as("premio escluso dal catalogo").isNull();
            assertThat(detail.status()).as("dettaglio").isEqualTo(404);
        }

        // richiesta (PT-04)
        Object ship = switch (a.get("shipping")) {
            case "Y" -> ADDRESS;
            case "EMPTY" -> Map.of();
            default -> null;
        };
        Resp r = requestRedemption(memberId, code, ship);
        String stockAfter = a.get("expStockAfter");
        if (a.is("expHttp", "202")) {
            assertThat(r.status()).as("richiesta → " + r.body()).isEqualTo(202);
            assertThat(r.body().path("status").asString()).isEqualTo("PENDING");
            assertThat(r.body().path("redemptionId").asString()).isNotBlank();
            assertThat(memberRedemptions(memberId)).isEqualTo(redemptionsBefore + 1);
        } else {
            assertThat(r.status()).as("richiesta → " + r.body()).isEqualTo(422);
            assertThat(r.code()).as("codice ammesso dalla specifica").isIn(split(a.get("expCodes")));
            if (!a.is("exactCode", "-")) {
                assertThat(r.code()).as("codice di oggi (ordine dei controlli)").isEqualTo(a.get("exactCode"));
            }
            assertThat(memberRedemptions(memberId)).as("nessuna richiesta creata").isEqualTo(redemptionsBefore);
        }
        if (rewardId != null && !"-".equals(stockAfter)) {
            assertThat(stock(rewardId)).as("stock residuo").isEqualTo(Integer.parseInt(stockAfter));
        }
    }

    @TestFactory
    Stream<DynamicTest> malformed() {
        return TestbookRwdCsv.rows("malformed.csv", r -> malformed(r.get("memberId"), r.get("rewardCode"), r.integer("expHttp")));
    }

    private void malformed(String memberToken, String rewardToken, int expHttp) {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of()).path("code").asString();
        Resp r;
        if ("NOBODY".equals(memberToken)) {
            r = send("POST", "/v1/portal/redemptions", null, null);
        } else {
            Map<String, Object> body = new LinkedHashMap<>();
            if (!"-".equals(memberToken)) {
                body.put("memberId", "M".equals(memberToken) ? memberId : memberToken);
            }
            if (!"-".equals(rewardToken)) {
                body.put("rewardCode", "RWD".equals(rewardToken) ? code : rewardToken);
            }
            r = send("POST", "/v1/portal/redemptions", null, body);
        }
        assertThat(r.status()).as("→ " + r.body()).isEqualTo(expHttp);
        assertThat(memberRedemptions(memberId)).isZero();
    }

    // ---------- SNP (snapshot del membro dai fatti) e STK (ultimo pezzo) ----------

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return Stream.of(
                scenario("TB-RWD-SNP-001", "member.registered rende richiedibile un membro prima ignoto", this::registeredMemberBecomesKnown),
                scenario("TB-RWD-SNP-002", "member.status.changed BLOCKED: catalogo invariato, richiesta 422", this::blockedByFact),
                scenario("TB-RWD-SNP-003", "member.status.changed ACTIVE: di nuovo richiedibile", this::reactivatedByFact),
                scenario("TB-RWD-SNP-004", "tier.upgraded a GOLD toglie il lucchetto", this::upgradeUnlocks),
                scenario("TB-RWD-SNP-005", "tier.downgraded a SILVER mette il lucchetto", this::downgradeLocks),
                scenario("TB-RWD-SNP-006", "member.segment.entered rende visibile il premio del segmento", this::segmentEntered),
                scenario("TB-RWD-SNP-007", "member.segment.left esclude il premio del segmento", this::segmentLeft),
                scenario("TB-RWD-STK-020", "ultimo pezzo, due richieste concorrenti", this::lastUnitConcurrent),
                scenario("TB-RWD-STK-021", "ultimo pezzo in sequenza", this::lastUnitSequential),
                scenario("TB-RWD-AUD-010", "richiesta respinta con 422: nessun fatto", this::rejectedRequestEmitsNothing));
    }

    void rejectedRequestEmitsNothing() {
        CLOCK.set(T0);
        String code = reward("LIVE", Map.of("stockTotal", 0)).path("code").asString();
        String blocked = member("BLOCKED", "GOLD");
        String active = member("ACTIVE", "GOLD");
        assertThat(requestRedemption(blocked, code, null).status()).isEqualTo(422);
        assertThat(requestRedemption(active, code, null).status()).isEqualTo(422);
        assertThat(memberFacts(blocked)).isZero();
        assertThat(memberFacts(active)).isZero();
    }

    void registeredMemberBecomesKnown() {
        CLOCK.set(T0);
        String memberId = fresh("MBR-TB");
        String code = reward("LIVE", Map.of()).path("code").asString();
        assertThat(requestRedemption(memberId, code, null).code()).isEqualTo("MEMBER_NOT_ACTIVE");
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.registered", memberId, null,
                Map.of("memberId", memberId, "status", "ACTIVE", "firstName", "Nuovo", "lastName", "Membro")));
        assertThat(requestRedemption(memberId, code, null).status()).isEqualTo(202);
    }

    void blockedByFact() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of()).path("code").asString();
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.status.changed", memberId, null,
                Map.of("previousStatus", "ACTIVE", "newStatus", "BLOCKED")));
        assertThat(catalogEntry(memberId, code)).isNotNull();
        assertThat(requestRedemption(memberId, code, null).code()).isEqualTo("MEMBER_NOT_ACTIVE");
    }

    void reactivatedByFact() {
        CLOCK.set(T0);
        String memberId = member("BLOCKED", "GOLD");
        String code = reward("LIVE", Map.of()).path("code").asString();
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.status.changed", memberId, null,
                Map.of("previousStatus", "BLOCKED", "newStatus", "ACTIVE")));
        assertThat(requestRedemption(memberId, code, null).status()).isEqualTo(202);
    }

    void upgradeUnlocks() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "SILVER");
        String code = reward("LIVE", Map.of("eligibleTiers", List.of("GOLD", "PLATINUM"))).path("code").asString();
        assertThat(catalogEntry(memberId, code).path("lockedByTier").isObject()).isTrue();
        awaitProcessed(publishFact("io.loyaltyhub.fact.tier.upgraded", memberId, null,
                Map.of("previousTier", "SILVER", "newTier", "GOLD")));
        JsonNode e = catalogEntry(memberId, code);
        assertThat(e.path("lockedByTier").isMissingNode() || e.path("lockedByTier").isNull()).isTrue();
        assertThat(requestRedemption(memberId, code, null).status()).isEqualTo(202);
    }

    void downgradeLocks() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of("eligibleTiers", List.of("GOLD"))).path("code").asString();
        awaitProcessed(publishFact("io.loyaltyhub.fact.tier.downgraded", memberId, null,
                Map.of("previousTier", "GOLD", "newTier", "SILVER")));
        assertThat(texts(catalogEntry(memberId, code).path("lockedByTier").path("requiredTiers"))).containsExactly("GOLD");
        assertThat(requestRedemption(memberId, code, null).code()).isEqualTo("TIER_NOT_ELIGIBLE");
    }

    void segmentEntered() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String seg = fresh("SEG-TB");
        String code = reward("LIVE", Map.of("eligibleSegments", List.of(seg))).path("code").asString();
        assertThat(catalogEntry(memberId, code)).isNull();
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.segment.entered", memberId, null, Map.of("segmentCode", seg)));
        assertThat(catalogEntry(memberId, code)).isNotNull();
        assertThat(requestRedemption(memberId, code, null).status()).isEqualTo(202);
    }

    void segmentLeft() {
        CLOCK.set(T0);
        String seg = fresh("SEG-TB");
        String memberId = member("ACTIVE", "GOLD", seg);
        String code = reward("LIVE", Map.of("eligibleSegments", List.of(seg))).path("code").asString();
        assertThat(catalogEntry(memberId, code)).isNotNull();
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.segment.left", memberId, null, Map.of("segmentCode", seg)));
        assertThat(catalogEntry(memberId, code)).isNull();
        assertThat(requestRedemption(memberId, code, null).code()).isEqualTo("REWARD_NOT_AVAILABLE");
    }

    void lastUnitConcurrent() throws Exception {
        CLOCK.set(T0);
        String m1 = member("ACTIVE", "GOLD");
        String m2 = member("ACTIVE", "GOLD");
        JsonNode rw = reward("LIVE", Map.of("stockTotal", 1));
        String code = rw.path("code").asString();
        CompletableFuture<Resp> a = CompletableFuture.supplyAsync(() -> requestRedemption(m1, code, null));
        CompletableFuture<Resp> b = CompletableFuture.supplyAsync(() -> requestRedemption(m2, code, null));
        List<Integer> statuses = List.of(a.get().status(), b.get().status());
        assertThat(statuses).containsExactlyInAnyOrder(202, 422);
        Resp loser = a.get().status() == 422 ? a.get() : b.get();
        assertThat(loser.code()).isEqualTo("REWARD_SOLD_OUT");
        assertThat(stock(rw.path("id").asString())).isZero();
    }

    void lastUnitSequential() {
        CLOCK.set(T0);
        String m1 = member("ACTIVE", "GOLD");
        String m2 = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of("stockTotal", 1)).path("code").asString();
        assertThat(requestRedemption(m1, code, null).status()).isEqualTo(202);
        Resp second = requestRedemption(m2, code, null);
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("REWARD_SOLD_OUT");
        assertThat(catalogEntry(m2, code).path("stockState").asString()).isEqualTo("SOLD_OUT");
    }

    // ---------- helper ----------

    /** Richieste precedenti del membro per il premio (dati di prova): attive FULFILLED, chiuse REJECTED/CANCELLED. */
    private void seedPrior(String memberId, String code, int active, int closed) {
        for (int i = 0; i < active + closed; i++) {
            RedemptionStatus st = i < active ? RedemptionStatus.FULFILLED : (i % 2 == 0 ? RedemptionStatus.REJECTED : RedemptionStatus.CANCELLED);
            String id = fresh("RDM-TB");
            redemptions.seed(new Redemption(id, memberId, code, "Premio testbook", 500, st, null, false, null, null, null,
                    id, T0.minus(Duration.ofDays(3)), null, T0.minus(Duration.ofDays(3)), "MEMBER:" + memberId));
        }
    }

    int memberRedemptions(String memberId) {
        return get("/v1/portal/redemptions?memberId=" + memberId).size();
    }

}
