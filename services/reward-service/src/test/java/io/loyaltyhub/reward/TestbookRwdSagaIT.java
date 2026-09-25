package io.loyaltyhub.reward;

import io.loyaltyhub.reward.TestbookRwdCsv.Row;
import io.loyaltyhub.reward.application.CouponService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.loyaltyhub.reward.TestbookRwdCsv.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD — saga della richiesta premio: stato × evento (SAG), evasione e pool esaurito (FUL), ruoli sulle
 * azioni manuali (ROL-001…021), confini del timeout (TMO). Il wallet è simulato pubblicandone i fatti su
 * {@code lh.facts.v1}. Oracolo: docs/03 §5, reward-service §3 e §5, F-RWD-05…07, BO-13, PT-13.
 */
class TestbookRwdSagaIT extends TestbookRwdBase {

    static final String SPENT = "io.loyaltyhub.fact.wallet.points.spent";
    static final String SPEND_REJECTED = "io.loyaltyhub.fact.wallet.spend.rejected";
    static final String REQUESTED = "io.loyaltyhub.fact.reward.redemption.requested";
    static final String CONFIRMED = "io.loyaltyhub.fact.reward.redemption.confirmed";
    static final String FULFILLED = "io.loyaltyhub.fact.reward.redemption.fulfilled";
    static final String REJECTED = "io.loyaltyhub.fact.reward.redemption.rejected";
    static final String CANCELLED = "io.loyaltyhub.fact.reward.redemption.cancelled";
    static final String COUPON_ISSUED = "io.loyaltyhub.fact.coupon.issued";

    @Autowired
    CouponService couponService;

    /** Una richiesta portata in uno stato di partenza. */
    record Case(String memberId, String rewardId, String rewardCode, String poolId, String id, String correlationId) {
    }

    // ---------- SAG: stato × evento ----------

    @TestFactory
    Stream<DynamicTest> stateByEvent() {
        return TestbookRwdCsv.rows("saga.csv", this::stateByEvent);
    }

    private void stateByEvent(Row row) {
        Case c = prepare(row.get("state"));
        int factsBefore = redemptionFacts(c.id()).size();
        int stockBefore = stock(c.rewardId());
        Resp http = fire(row.get("event"), c);
        if (!row.is("expHttp", "-")) {
            assertThat(http).as("risposta HTTP").isNotNull();
            assertThat(http.status()).as("→ " + http.body()).isEqualTo(row.integer("expHttp"));
        }
        JsonNode after = redemption(c.id());
        assertThat(after.path("status").asString()).as("stato finale " + after).isEqualTo(row.get("expStatus"));
        List<JsonNode> all = redemptionFacts(c.id());
        List<JsonNode> newFacts = all.subList(factsBefore, all.size());
        String fact = row.get("expFact");
        switch (fact.split(":")[0]) {
            case "none" -> assertThat(newFacts).as("nessun nuovo fatto reward.redemption.*").isEmpty();
            case "confirmed" -> assertThat(types(newFacts)).contains(CONFIRMED);
            case "fulfilled" -> assertThat(types(newFacts)).contains(FULFILLED);
            case "rejected" -> assertThat(newFacts).anySatisfy(f -> {
                assertThat(f.path("type").asString()).isEqualTo(REJECTED);
                assertThat(f.path("data").path("reason").asString()).isEqualTo(fact.split(":")[1]);
            });
            case "cancelled" -> assertThat(newFacts).anySatisfy(f -> {
                assertThat(f.path("type").asString()).isEqualTo(CANCELLED);
                assertThat(f.path("data").path("refund").asBoolean()).isEqualTo(Boolean.parseBoolean(fact.split(":")[1]));
            });
            default -> { } // "-": la specifica non dice se emettere un fatto
        }
        int delta = row.is("expStockDelta", "+1") ? 1 : 0;
        assertThat(stock(c.rewardId())).as("stock").isEqualTo(stockBefore + delta);
    }

    /** Porta una richiesta nuova nello stato di partenza (richiesta alle {@link #T0}, premio con stock 10). */
    Case prepare(String state) {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String poolId = null;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stockTotal", 10);
        switch (state) {
            case "CONFIRMED_ATTENTION" -> {
                poolId = pool(0, 30);
                f.put("type", "COUPON");
                f.put("fulfilment", "AUTO_COUPON");
                f.put("couponPoolId", poolId);
            }
            case "FULFILLED" -> f.put("fulfilment", "INSTANT");
            default -> f.put("fulfilment", "MANUAL");
        }
        JsonNode rw = reward("LIVE", f);
        Case c = request(memberId, rw, poolId);
        switch (state) {
            case "PENDING" -> { }
            case "CONFIRMED_MANUAL" -> spend(c, "CONFIRMED");
            case "CONFIRMED_ATTENTION" -> {
                awaitProcessed(spent(c));
                assertThat(redemption(c.id()).path("needsAttention").asBoolean()).isTrue();
            }
            case "FULFILLED" -> spend(c, "FULFILLED");
            case "REJECTED" -> {
                awaitProcessed(publishFact(SPEND_REJECTED, c.memberId(), c.correlationId(),
                        Map.of("redemptionId", c.id(), "reason", "INSUFFICIENT_BALANCE", "requested", 500, "available", 100)));
                assertThat(redemption(c.id()).path("status").asString()).isEqualTo("REJECTED");
            }
            case "CANCELLED_MEMBER" -> assertThat(send("POST", "/v1/portal/redemptions/" + c.id() + "/cancel?memberId=" + memberId,
                    null, null).status()).isEqualTo(200);
            case "CANCELLED_REFUND" -> {
                spend(c, "CONFIRMED");
                assertThat(send("POST", "/v1/redemptions/" + c.id() + "/cancel", "CARE:testbook.care",
                        Map.of("reason", "Premio non più disponibile")).status()).isEqualTo(200);
            }
            default -> throw new IllegalArgumentException(state);
        }
        return c;
    }

    Case request(String memberId, JsonNode rw, String poolId) {
        Resp r = requestRedemption(memberId, rw.path("code").asString(), null);
        assertThat(r.status()).as("richiesta → " + r.body()).isEqualTo(202);
        return new Case(memberId, rw.path("id").asString(), rw.path("code").asString(), poolId,
                r.body().path("redemptionId").asString(), r.body().path("correlationId").asString());
    }

    String spent(Case c) {
        return publishFact(SPENT, c.memberId(), c.correlationId(), Map.of("redemptionId", c.id(), "ledgerEntryId", "LE-" + c.id(),
                "currency", "PTS", "amount", 500, "balanceAfter", 1000));
    }

    void spend(Case c, String expected) {
        awaitProcessed(spent(c));
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo(expected);
    }

    /** Esegue l'evento della colonna {@code event}; ritorna la risposta HTTP se l'evento è una chiamata. */
    Resp fire(String event, Case c) {
        return switch (event) {
            case "SPENT" -> {
                awaitProcessed(spent(c));
                yield null;
            }
            case "REJECT" -> {
                awaitProcessed(publishFact(SPEND_REJECTED, c.memberId(), c.correlationId(),
                        Map.of("redemptionId", c.id(), "reason", "INSUFFICIENT_BALANCE", "requested", 500, "available", 100)));
                yield null;
            }
            case "TIMEOUT" -> send("POST", "/v1/demo/jobs/timeout-redemptions?asOf=" + T0.plusSeconds(601), "ADMIN:testbook", null);
            case "MEMBER_CANCEL" -> send("POST", "/v1/portal/redemptions/" + c.id() + "/cancel?memberId=" + c.memberId(), null, null);
            case "CARE_CANCEL" -> send("POST", "/v1/redemptions/" + c.id() + "/cancel", "CARE:testbook.care", Map.of("reason", "Motivo testbook"));
            case "CARE_FULFIL" -> send("POST", "/v1/redemptions/" + c.id() + "/fulfil", "CARE:testbook.care", Map.of("note", "Spedito"));
            case "RETRY" -> {
                if (c.poolId() != null) {
                    call("POST", "/v1/coupon-pools/" + c.poolId() + "/generate", "ADMIN:testbook", Map.of("count", 1));
                }
                yield send("POST", "/v1/redemptions/" + c.id() + "/retry-fulfilment", "CARE:testbook.care", null);
            }
            default -> throw new IllegalArgumentException(event);
        };
    }

    /** Fatti {@code reward.redemption.*} emessi per la richiesta (outbox, in ordine di scrittura). */
    List<JsonNode> redemptionFacts(String id) {
        return emitted(id).stream().filter(e -> e.path("type").asString().startsWith("io.loyaltyhub.fact.reward.redemption.")).toList();
    }

    static List<String> types(List<JsonNode> facts) {
        return facts.stream().map(f -> f.path("type").asString()).toList();
    }

    // ---------- ROL: ruoli sulle azioni manuali ----------

    // TESTBOOK: ambiguo, vedi TB-RWD-ROL-015, TB-RWD-ROL-018
    @TestFactory
    Stream<DynamicTest> roles() {
        return TestbookRwdCsv.rows("roles-redemption.csv", row -> {
            String action = row.get("action");
            Case c = prepare("RETRY".equals(action) ? "CONFIRMED_ATTENTION" : "CONFIRMED_MANUAL");
            String actor = actorFor(row.get("role"));
            Resp r = switch (action) {
                case "FULFIL" -> send("POST", "/v1/redemptions/" + c.id() + "/fulfil", actor, Map.of("note", "Spedito"));
                case "CANCEL" -> send("POST", "/v1/redemptions/" + c.id() + "/cancel", actor, Map.of("reason", "Motivo"));
                default -> {
                    call("POST", "/v1/coupon-pools/" + c.poolId() + "/generate", "ADMIN:testbook", Map.of("count", 1));
                    yield send("POST", "/v1/redemptions/" + c.id() + "/retry-fulfilment", actor, null);
                }
            };
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            String expected = r.status() == 403 ? "CONFIRMED" : "CANCEL".equals(action) ? "CANCELLED" : "FULFILLED";
            assertThat(redemption(c.id()).path("status").asString()).isEqualTo(expected);
        });
    }

    // ---------- TMO: confini del timeout ----------

    @TestFactory
    Stream<DynamicTest> timeout() {
        return TestbookRwdCsv.rows("timeout.csv", row -> {
            Case c = prepare("PENDING");
            int stockBefore = stock(c.rewardId());
            String asOf = row.get("asOf");
            Resp r;
            if (asOf.startsWith("CLOCK")) {
                CLOCK.set(T0.plus(duration(asOf.substring("CLOCK".length()))));
                r = send("POST", "/v1/demo/jobs/timeout-redemptions", "ADMIN:testbook", null);
            } else {
                r = send("POST", "/v1/demo/jobs/timeout-redemptions?asOf=" + T0.plus(duration(asOf)), "ADMIN:testbook", null);
            }
            assertThat(r.status()).isEqualTo(200);
            JsonNode after = redemption(c.id());
            assertThat(after.path("status").asString()).isEqualTo(row.get("expStatus"));
            if (row.is("expStatus", "REJECTED")) {
                assertThat(after.path("rejectReason").asString()).isEqualTo("TIMEOUT");
                assertThat(stock(c.rewardId())).isEqualTo(stockBefore + 1);
                assertThat(facts(REJECTED, c.id())).hasSize(1);
            } else {
                assertThat(stock(c.rewardId())).isEqualTo(stockBefore);
            }
        });
    }

    // ---------- FUL: evasione, pool esaurito, errori dell'operatore ----------

    @TestFactory
    Stream<DynamicTest> fulfilment() {
        return Stream.of(
                scenario("TB-RWD-FUL-001", "AUTO_COUPON con pool disponibile", this::autoCouponFulfilled),
                // TESTBOOK: ambiguo, vedi TB-RWD-FUL-002 («oggi + validity_days»)
                scenario("TB-RWD-FUL-002", "scadenza del coupon emesso per la richiesta", this::couponExpiryFromValidity),
                scenario("TB-RWD-FUL-003", "AUTO_COUPON con pool vuoto → needsAttention", this::emptyPoolNeedsAttention),
                scenario("TB-RWD-FUL-004", "pool con un solo codice e due richieste", this::lastCodeThenAttention),
                scenario("TB-RWD-FUL-005", "retry-fulfilment con pool ancora vuoto", this::retryStillEmpty),
                // TESTBOOK: ambiguo, vedi TB-RWD-FUL-006 (AUTO_COUPON senza pool)
                scenario("TB-RWD-FUL-006", "AUTO_COUPON su premio senza pool", this::autoCouponWithoutPool),
                scenario("TB-RWD-FUL-007", "INSTANT evaso subito", this::instantFulfilled),
                scenario("TB-RWD-FUL-008", "MANUAL resta CONFIRMED in «Da evadere»", this::manualWaits),
                scenario("TB-RWD-FUL-009", "evasione manuale con nota e tracking", this::manualFulfilWithTracking),
                scenario("TB-RWD-FUL-010", "evasione manuale con nota vuota", () -> operatorError("fulfil", Map.of("note", "  "), 422, "NOTE_REQUIRED")),
                scenario("TB-RWD-FUL-011", "evasione manuale senza corpo", () -> operatorError("fulfil", null, 0, null)),
                scenario("TB-RWD-FUL-012", "annullo CARE con motivo vuoto", () -> operatorError("cancel", Map.of("reason", "  "), 422, "REASON_REQUIRED")),
                scenario("TB-RWD-FUL-013", "annullo CARE senza corpo", () -> operatorError("cancel", null, 0, null)),
                scenario("TB-RWD-FUL-014", "annullo di una CONFIRMED con coupon collegato", this::cancelVoidsCoupon),
                scenario("TB-RWD-FUL-015", "spesa rielaborata su richiesta già evasa", this::reprocessedSpendOneCoupon),
                scenario("TB-RWD-FUL-016", "stessa spesa riconsegnata (stesso id)", this::redeliveredSpend),
                scenario("TB-RWD-FUL-017", "spesa per richiesta sconosciuta", () -> unknownRedemption(SPENT)),
                scenario("TB-RWD-FUL-018", "rifiuto per richiesta sconosciuta", () -> unknownRedemption(SPEND_REJECTED)),
                scenario("TB-RWD-FUL-019", "fulfil su id inesistente", () -> notFound("fulfil", Map.of("note", "x"))),
                scenario("TB-RWD-FUL-020", "cancel su id inesistente", () -> notFound("cancel", Map.of("reason", "x"))),
                scenario("TB-RWD-FUL-021", "retry-fulfilment su id inesistente", () -> notFound("retry-fulfilment", null)),
                scenario("TB-RWD-FUL-022", "risposta 202 e fatto requested", this::acceptedAndRequestedFact),
                scenario("TB-RWD-FUL-023", "rifiuto del wallet con motivo MEMBER_NOT_ACTIVE", this::rejectedWithWalletReason),
                scenario("TB-RWD-FUL-024", "annullo dal portale con memberId di un altro membro", this::memberCancelOtherMember),
                // TESTBOOK: ambiguo, vedi TB-RWD-FUL-025 (annullo senza memberId)
                scenario("TB-RWD-FUL-025", "annullo dal portale senza memberId", this::memberCancelWithoutMember),
                scenario("TB-RWD-FUL-026", "dettaglio dal portale con memberId di un altro membro", this::portalGetOtherMember),
                scenario("TB-RWD-FUL-027", "elenco dal portale senza memberId", this::portalListWithoutMember),
                // TESTBOOK: ambiguo, vedi TB-RWD-FUL-028 («correlationId della richiesta HTTP»)
                scenario("TB-RWD-FUL-028", "intestazione X-Correlation-Id sulla richiesta", this::correlationHeader),
                scenario("TB-RWD-FUL-029", "filtri rewardCode, from, to dell'elenco richieste", this::listFilters),
                scenario("TB-RWD-FUL-030", "indirizzo di spedizione conservato", this::shippingKept),
                scenario("TB-RWD-AUD-007", "audit dell'evasione manuale", () -> auditOperator("fulfil", Map.of("note", "Spedito"))),
                scenario("TB-RWD-AUD-008", "audit dell'annullo con rimborso", () -> auditOperator("cancel", Map.of("reason", "Motivo"))));
    }

    void correlationHeader() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of()).path("code").asString();
        String header = "01TBCORR" + System.nanoTime();
        Resp r = send("POST", "/v1/portal/redemptions", null, Map.of("memberId", memberId, "rewardCode", code),
                Map.of("X-Correlation-Id", header));
        assertThat(r.status()).isEqualTo(202);
        String id = r.body().path("redemptionId").asString();
        JsonNode requested = facts(REQUESTED, id).get(0);
        assertThat(r.body().path("correlationId").asString()).isEqualTo(requested.path("id").asString());
        assertThat(requested.path("lhcorrelationid").asString()).isEqualTo(requested.path("id").asString());
    }

    void listFilters() {
        String memberId = member("ACTIVE", "GOLD");
        String a = reward("LIVE", Map.of()).path("code").asString();
        String b = reward("LIVE", Map.of()).path("code").asString();
        CLOCK.set(T0);
        String first = requestRedemption(memberId, a, null).body().path("redemptionId").asString();
        CLOCK.set(T0.plusSeconds(3600));
        String second = requestRedemption(memberId, a, null).body().path("redemptionId").asString();
        String other = requestRedemption(memberId, b, null).body().path("redemptionId").asString();
        String base = "/v1/redemptions?memberId=" + memberId;
        assertThat(ids(get(base + "&rewardCode=" + a))).containsExactlyInAnyOrder(first, second);
        assertThat(ids(get(base + "&from=" + T0.plusSeconds(1800)))).containsExactlyInAnyOrder(second, other);
        assertThat(ids(get(base + "&to=" + T0.plusSeconds(3600)))).containsExactly(first);
        assertThat(ids(get(base + "&from=" + T0))).contains(first);
        assertThat(other).isNotBlank();
    }

    static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    void shippingKept() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of("type", "PHYSICAL", "fulfilment", "MANUAL")).path("code").asString();
        String id = requestRedemption(memberId, code, ADDRESS).body().path("redemptionId").asString();
        JsonNode r = redemption(id);
        assertThat(r.path("shipping").path("city").asString()).isEqualTo("Torino");
        assertThat(r.path("shipping").path("street").asString()).isEqualTo("Via Roma 1");
    }

    void auditOperator(String op, Map<String, Object> body) {
        Case c = prepare("CONFIRMED_MANUAL");
        call("POST", "/v1/redemptions/" + c.id() + "/" + op, "CARE:anna.care", body);
        List<JsonNode> a = audits("REDEMPTION", c.id());
        assertThat(a).isNotEmpty();
        JsonNode last = a.get(a.size() - 1);
        assertThat(last.path("data").path("action").asString()).isEqualTo("UPDATE");
        assertThat(last.path("lhactor").asString()).isEqualTo("CARE:anna.care");
    }

    void autoCouponFulfilled() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String poolId = pool(3, 30);
        JsonNode rw = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId, "stockTotal", 10));
        Case c = request(memberId, rw, poolId);
        awaitProcessed(spent(c));
        JsonNode done = awaitStatus(c.id(), "FULFILLED");
        String couponCode = done.path("couponCode").asString();
        String prefix = get("/v1/coupon-pools/" + poolId).path("prefix").asString();
        assertThat(couponCode).startsWith(prefix + "-");
        JsonNode coupon = get("/v1/coupons/" + couponCode);
        assertThat(coupon.path("status").asString()).isEqualTo("ISSUED");
        assertThat(coupon.path("memberId").asString()).isEqualTo(memberId);
        assertThat(coupon.path("origin").asString()).isEqualTo("REDEMPTION");
        assertThat(coupon.path("redemptionId").asString()).isEqualTo(c.id());
        for (String type : List.of(CONFIRMED, COUPON_ISSUED, FULFILLED)) {
            assertThat(awaitFact(type, c.id()).path("lhcorrelationid").asString()).as(type).isEqualTo(c.correlationId());
        }
        List<String> history = new ArrayList<>();
        done.path("history").forEach(h -> history.add(h.path("status").asString()));
        assertThat(history).containsExactly("PENDING", "CONFIRMED", "FULFILLED");
    }

    void couponExpiryFromValidity() {
        Instant issue = Instant.parse("2026-10-20T10:00:00Z");
        CLOCK.set(issue);
        String memberId = member("ACTIVE", "GOLD");
        String poolId = pool(1, 10);
        JsonNode rw = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId));
        Case c = request(memberId, rw, poolId);
        awaitProcessed(spent(c));
        String code = awaitStatus(c.id(), "FULFILLED").path("couponCode").asString();
        assertThat(get("/v1/coupons/" + code).path("expiresAt").asString()).isEqualTo("2026-10-30T10:00:00Z");
    }

    void emptyPoolNeedsAttention() {
        Case c = prepare("CONFIRMED_ATTENTION");
        JsonNode r = redemption(c.id());
        assertThat(r.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(r.path("needsAttention").asBoolean()).isTrue();
        assertThat(r.path("couponCode").isMissingNode() || r.path("couponCode").isNull()).isTrue();
        assertThat(facts(FULFILLED, c.id())).isEmpty();
        assertThat(get("/v1/redemptions?needsAttention=true&memberId=" + c.memberId()).path("items").toString()).contains(c.id());
    }

    void lastCodeThenAttention() {
        CLOCK.set(T0);
        String poolId = pool(1, 30);
        JsonNode rw = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId));
        Case first = request(member("ACTIVE", "GOLD"), rw, poolId);
        Case second = request(member("ACTIVE", "GOLD"), rw, poolId);
        awaitProcessed(spent(first));
        awaitProcessed(spent(second));
        assertThat(redemption(first.id()).path("status").asString()).isEqualTo("FULFILLED");
        JsonNode s = redemption(second.id());
        assertThat(s.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(s.path("needsAttention").asBoolean()).isTrue();
    }

    void retryStillEmpty() {
        Case c = prepare("CONFIRMED_ATTENTION");
        Resp r = send("POST", "/v1/redemptions/" + c.id() + "/retry-fulfilment", "CARE:testbook.care", null);
        assertThat(r.status()).isEqualTo(409);
        JsonNode after = redemption(c.id());
        assertThat(after.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(after.path("needsAttention").asBoolean()).isTrue();
    }

    void autoCouponWithoutPool() {
        CLOCK.set(T0);
        JsonNode rw = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON"));
        Case c = request(member("ACTIVE", "GOLD"), rw, null);
        awaitProcessed(spent(c));
        JsonNode r = redemption(c.id());
        assertThat(r.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(r.path("needsAttention").asBoolean()).isTrue();
    }

    void instantFulfilled() {
        Case c = prepare("FULFILLED");
        JsonNode r = redemption(c.id());
        assertThat(r.path("couponCode").isMissingNode() || r.path("couponCode").isNull()).isTrue();
        assertThat(facts(FULFILLED, c.id()).get(0).path("data").has("couponCode")).isFalse();
    }

    void manualWaits() {
        Case c = prepare("CONFIRMED_MANUAL");
        assertThat(get("/v1/redemptions?status=CONFIRMED&fulfilment=MANUAL&memberId=" + c.memberId()).path("items").toString())
                .contains(c.id());
        assertThat(facts(CONFIRMED, c.id())).hasSize(1);
    }

    void manualFulfilWithTracking() {
        Case c = prepare("CONFIRMED_MANUAL");
        Resp r = send("POST", "/v1/redemptions/" + c.id() + "/fulfil", "CARE:testbook.care",
                Map.of("note", "Spedito con corriere", "tracking", "AUR-1"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("status").asString()).isEqualTo("FULFILLED");
        assertThat(r.body().path("fulfilmentNote").asString()).isEqualTo("Spedito con corriere · tracking AUR-1");
        assertThat(r.body().path("history").toString()).contains("CARE:testbook.care");
        assertThat(facts(FULFILLED, c.id()).get(0).path("data").path("note").asString()).contains("AUR-1");
    }

    /** {@code expHttp = 0}: richiesta malformata → 400 o 422 (mai un errore interno). */
    void operatorError(String op, Map<String, Object> body, int expHttp, String code) {
        Case c = prepare("CONFIRMED_MANUAL");
        Resp r = send("POST", "/v1/redemptions/" + c.id() + "/" + op, "CARE:testbook.care", body);
        if (expHttp == 0) {
            assertThat(r.status()).as("richiesta senza corpo → " + r.body()).isIn(400, 422);
        } else {
            assertThat(r.status()).isEqualTo(expHttp);
            assertThat(r.code()).isEqualTo(code);
        }
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo("CONFIRMED");
    }

    void cancelVoidsCoupon() {
        Case c = prepare("CONFIRMED_MANUAL");
        String poolId = pool(1, 30);
        String couponCode = couponService.issue(poolId, c.memberId(), c.rewardCode(), "REDEMPTION", c.id(), null, null)
                .orElseThrow().code();
        int stockBefore = stock(c.rewardId());
        Resp r = send("POST", "/v1/redemptions/" + c.id() + "/cancel", "CARE:testbook.care", Map.of("reason", "Errore di spedizione"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(get("/v1/coupons/" + couponCode).path("status").asString()).isEqualTo("VOID");
        assertThat(stock(c.rewardId())).isEqualTo(stockBefore + 1);
        assertThat(facts(CANCELLED, c.id())).anySatisfy(f -> assertThat(f.path("data").path("refund").asBoolean()).isTrue());
    }

    void reprocessedSpendOneCoupon() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String poolId = pool(3, 30);
        JsonNode rw = reward("LIVE", Map.of("type", "COUPON", "fulfilment", "AUTO_COUPON", "couponPoolId", poolId));
        Case c = request(memberId, rw, poolId);
        awaitProcessed(spent(c));
        awaitStatus(c.id(), "FULFILLED");
        awaitProcessed(spent(c));
        assertThat(get("/v1/portal/coupons?memberId=" + memberId).size()).isEqualTo(1);
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo("FULFILLED");
    }

    void redeliveredSpend() {
        Case c = prepare("PENDING");
        String id = "01TBDUP" + System.nanoTime();
        Map<String, Object> data = Map.of("redemptionId", c.id(), "ledgerEntryId", "LE-" + c.id(), "currency", "PTS", "amount", 500, "balanceAfter", 1000);
        publishFactWithId(id, SPENT, c.memberId(), c.correlationId(), data);
        publishFactWithId(id, SPENT, c.memberId(), c.correlationId(), data);
        // marcatore: stessa partizione, elaborato dopo i due invii
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.updated", c.memberId(), null, Map.of("status", "ACTIVE")));
        assertThat(facts(CONFIRMED, c.id())).hasSize(1);
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo("CONFIRMED");
    }

    void unknownRedemption(String type) {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        String unknown = fresh("RDM-NOPE");
        String eventId = publishFact(type, memberId, null, Map.of("redemptionId", unknown, "reason", "INSUFFICIENT_BALANCE",
                "currency", "PTS", "amount", 500));
        awaitProcessed(eventId);
        drainOutbox();
        assertThat(tap().matching("lh.dlq.v1", r -> r.event().toString().contains(eventId))).isEmpty();
        assertThat(send("GET", "/v1/redemptions/" + unknown, null, null).status()).isEqualTo(404);
    }

    void notFound(String op, Map<String, Object> body) {
        assertThat(send("POST", "/v1/redemptions/" + fresh("RDM-NOPE") + "/" + op, "CARE:testbook.care", body).status()).isEqualTo(404);
    }

    void acceptedAndRequestedFact() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        JsonNode rw = reward("LIVE", Map.of("band", "F1"));
        Resp r = requestRedemption(memberId, rw.path("code").asString(), null);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().path("status").asString()).isEqualTo("PENDING");
        String id = r.body().path("redemptionId").asString();
        String correlationId = r.body().path("correlationId").asString();
        JsonNode requested = facts(REQUESTED, id).get(0);
        assertThat(requested.path("id").asString()).isEqualTo(correlationId);
        assertThat(requested.path("data").path("pointsCost").asLong()).isEqualTo(500);
        assertThat(requested.path("data").path("currency").asString()).isEqualTo("PTS");
        assertThat(redemption(id).path("pointsCost").asLong()).isEqualTo(500);
    }

    void rejectedWithWalletReason() {
        Case c = prepare("PENDING");
        int stockBefore = stock(c.rewardId());
        awaitProcessed(publishFact(SPEND_REJECTED, c.memberId(), c.correlationId(),
                Map.of("redemptionId", c.id(), "reason", "MEMBER_NOT_ACTIVE")));
        JsonNode r = redemption(c.id());
        assertThat(r.path("status").asString()).isEqualTo("REJECTED");
        assertThat(r.path("rejectReason").asString()).isEqualTo("MEMBER_NOT_ACTIVE");
        assertThat(stock(c.rewardId())).isEqualTo(stockBefore + 1);
    }

    void memberCancelOtherMember() {
        Case c = prepare("PENDING");
        String other = member("ACTIVE", "GOLD");
        assertThat(send("POST", "/v1/portal/redemptions/" + c.id() + "/cancel?memberId=" + other, null, null).status()).isEqualTo(404);
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo("PENDING");
    }

    void memberCancelWithoutMember() {
        Case c = prepare("PENDING");
        Resp r = send("POST", "/v1/portal/redemptions/" + c.id() + "/cancel", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(redemption(c.id()).path("status").asString()).isEqualTo("CANCELLED");
    }

    void portalGetOtherMember() {
        Case c = prepare("PENDING");
        String other = member("ACTIVE", "GOLD");
        assertThat(send("GET", "/v1/portal/redemptions/" + c.id() + "?memberId=" + other, null, null).status()).isEqualTo(404);
    }

    void portalListWithoutMember() {
        assertThat(send("GET", "/v1/portal/redemptions", null, null).status()).isEqualTo(400);
    }
}
