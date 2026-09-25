package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-E2E — richiesta premio tra reward e wallet (saga, docs/03 §5, reward-service §5, wallet-service §5):
 * premio con coupon (CPN), punti non sufficienti (INS), premio fisico con evasione e annullo (PHY), wallet addormentato
 * a metà saga e timeout (SAG). Membri nuovi a ogni caso; il saldo di partenza si prepara con una rettifica GOODWILL.
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookE2eRedemptionIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookE2eRedemptionIT extends TestbookE2eSupportIT {

    private static final EmbeddedPostgres PG = startPg();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    /** Orologio del caso, registrato solo da questa classe (vedi {@link TestbookE2eJourneysIT.Today}). */
    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-09-24T10:00:00Z");
        }
    }

    /** Membro nuovo con saldo PTS voluto (benvenuto 100 + rettifica). */
    Member memberWith(long balance) {
        Member m = newMember();
        if (balance > 100) {
            credit(m.id(), balance - 100);
        }
        assertThat(pts(m.id())).isEqualTo(balance);
        return m;
    }

    // ---------- CPN, INS, PHY ----------

    @TestFactory
    Stream<DynamicTest> premi() {
        return rows("premi.csv", this::premi);
    }

    private void premi(Row row) {
        String reward = row.get("premio");
        Member m = memberWith(row.num("saldo"));
        Integer stockBefore = stock(reward);
        Resp r = redeem(m.id(), reward, !row.is("caso", "senza-indirizzo"));
        if (row.is("caso", "senza-indirizzo")) {
            assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
            assertThat(r.code()).isEqualTo("SHIPPING_REQUIRED");
            quiet();
            assertThat(count("SELECT count(*) FROM reward.redemption WHERE member_id = ?", m.id())).isZero();
            assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type LIKE 'reward.%'", m.id())).isZero();
            assertThat(stock(reward)).isEqualTo(stockBefore);
            assertThat(pts(m.id())).isEqualTo(row.num("saldoFinale"));
            return;
        }
        assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
        assertThat(r.body().path("status").asString()).isEqualTo("PENDING");
        String id = r.body().path("redemptionId").asString();
        String cid = r.body().path("correlationId").asString();
        quiet();
        switch (row.get("caso")) {
            case "evasione", "annullo-evasa" -> {
                assertThat(redemptionStatus(id)).as("MANUAL: resta in coda a BO-13").isEqualTo("CONFIRMED");
                ok(send("POST", "/v1/redemptions/" + id + "/fulfil", CARE, Map.of("note", "Spedito con corriere", "tracking", "TRK-1")), 200);
                quiet();
                if (row.is("caso", "annullo-evasa")) {
                    Map<String, List<String>> before = memberState(m.id());
                    Resp cancel = send("POST", "/v1/redemptions/" + id + "/cancel", CARE, Map.of("reason", "Ripensamento"));
                    assertThat(cancel.status()).isEqualTo(409);
                    assertThat(cancel.code()).isEqualTo("REDEMPTION_NOT_CANCELLABLE");
                    quiet();
                    assertThat(memberState(m.id())).isEqualTo(before);
                }
            }
            case "annullo" -> {
                assertThat(redemptionStatus(id)).isEqualTo("CONFIRMED");
                ok(send("POST", "/v1/redemptions/" + id + "/cancel", CARE, Map.of("reason", "Articolo danneggiato in magazzino")), 200);
                quiet();
            }
            default -> { }
        }
        String[] expected = row.get("esito").split(":");
        JsonNode done = redemption(id);
        assertThat(done.path("status").asString()).isEqualTo(expected[0]);
        List<Ev> chain = assertChain(cid, row.get("catena"));
        assertTrace(cid, "COMPLETE");
        assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
        assertThat(pts(m.id())).isEqualTo(row.num("saldoFinale"));
        long cost = row.num("saldo") - (row.is("esito", "FULFILLED") ? row.num("saldoFinale") : 0);
        switch (row.get("caso")) {
            case "coupon" -> {
                assertThat(done.path("couponCode").asString()).matches("CAF-[A-Z2-9]{4}-[A-Z2-9]{4}");
                JsonNode coupon = get("/v1/portal/coupons?memberId=" + m.id()).get(0);
                assertThat(coupon.path("code").asString()).isEqualTo(done.path("couponCode").asString());
                assertThat(coupon.path("status").asString()).isEqualTo("ISSUED");
                assertThat(coupon.path("origin").asString()).isEqualTo("REDEMPTION");
                assertThat(coupon.path("expiresAt").isMissingNode() || coupon.path("expiresAt").isNull()).isFalse();
                assertThat(ledger(m.id(), cid)).containsExactly("SPEND:PTS:" + cost);
                assertThat(stock(reward)).isEqualTo(stockBefore - 1);
                assertBridgedFromConfirmed(chain);
            }
            case "donazione" -> {
                assertThat(done.path("couponCode").isNull() || done.path("couponCode").isMissingNode()).isTrue();
                assertThat(stock(reward)).isEqualTo(stockBefore);
                assertThat(ledger(m.id(), cid)).containsExactly("SPEND:PTS:500");
            }
            case "insufficiente" -> {
                assertThat(done.path("rejectReason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
                Ev rejected = ofKey(chain, "FACT:wallet.spend.rejected").get(0);
                assertThat(rejected.data().path("reason").asString()).isEqualTo("INSUFFICIENT_BALANCE");
                assertThat(rejected.data().path("requested").asLong()).isEqualTo(500);
                assertThat(rejected.data().path("available").asLong()).isEqualTo(row.num("saldo"));
                assertThat(ofKey(chain, "FACT:reward.redemption.rejected").get(0).data().path("reason").asString())
                        .isEqualTo("INSUFFICIENT_BALANCE");
                assertThat(stock(reward)).as("stock ripristinato").isEqualTo(stockBefore);
                assertThat(ledger(m.id(), cid)).as("nessuna spesa").isEmpty();
            }
            case "evasione", "annullo-evasa" -> {
                assertThat(ofKey(chain, "FACT:reward.redemption.fulfilled").get(0).data().path("note").asString())
                        .contains("Spedito con corriere");
                assertThat(stock(reward)).isEqualTo(stockBefore - 1);
                assertThat(ledger(m.id(), cid)).containsExactly("SPEND:PTS:1500");
            }
            case "annullo" -> {
                Ev cancelled = ofKey(chain, "FACT:reward.redemption.cancelled").get(0);
                assertThat(cancelled.data().path("refund").asBoolean()).isTrue();
                assertThat(ofKey(chain, "FACT:wallet.points.refunded").get(0).data().path("amount").asLong()).isEqualTo(1500);
                assertThat(ledger(m.id(), cid)).containsExactly("REFUND:PTS:1500", "SPEND:PTS:1500");
                assertThat(stock(reward)).as("stock ripristinato").isEqualTo(stockBefore);
                // docs/03 §4.2, Q-160: lotto nuovo con scadenza = max(scadenza più lontana dei lotti consumati, oggi + 30 gg).
                Instant consumedMax = jdbc.sql("""
                                SELECT max(l.expires_at) FROM wallet.lot_consumption c JOIN wallet.points_lot l ON l.id = c.lot_id
                                JOIN wallet.ledger_entry e ON e.id = c.ledger_entry_id
                                WHERE e.member_id = ? AND e.type = 'SPEND' AND e.redemption_id = ?
                                """).params(m.id(), id).query(java.sql.Timestamp.class).single().toInstant();
                Instant refundLot = jdbc.sql("""
                                SELECT l.expires_at FROM wallet.points_lot l JOIN wallet.ledger_entry e ON e.id = l.ledger_entry_id
                                WHERE e.member_id = ? AND e.type = 'REFUND' AND e.redemption_id = ?
                                """).params(m.id(), id).query(java.sql.Timestamp.class).single().toInstant();
                assertThat(consumedMax).as("i lotti consumati scadono oltre oggi + 30 gg").isAfter(clock.instant().plus(Duration.ofDays(30)));
                assertThat(refundLot).as("scadenza del lotto di rimborso").isEqualTo(consumedMax);
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
    }

    /** M4.6: la conferma rientra dal ponte come azione {@code reward.redeemed} causata dal fatto {@code confirmed}. */
    private static void assertBridgedFromConfirmed(List<Ev> chain) {
        Ev confirmed = ofKey(chain, "FACT:reward.redemption.confirmed").get(0);
        Ev redeemed = ofKey(chain, "ACTION:reward.redeemed").get(0);
        assertThat(redeemed.causation()).isEqualTo(confirmed.id());
        assertThat(redeemed.data().path("rewardCode").asString()).isEqualTo(confirmed.data().path("rewardCode").asString());
        assertThat(redeemed.data().path("pointsCost").asLong()).isEqualTo(confirmed.data().path("pointsCost").asLong());
    }

    // ---------- SAG: wallet addormentato e timeout ----------

    @TestFactory
    Stream<DynamicTest> saga() {
        return rows("saga.csv", this::saga);
    }

    private void saga(Row row) {
        String reward = "RWD-COFFEE-5";
        Member m = memberWith(500);
        Integer stockBefore = stock(reward);
        String id;
        String cid;
        if (row.is("caso", "timeout-evasa")) {
            Resp r = redeem(m.id(), reward, false);
            id = r.body().path("redemptionId").asString();
            cid = r.body().path("correlationId").asString();
            quiet();
            assertThat(redemptionStatus(id)).isEqualTo("FULFILLED");
            ok(send("POST", "/v1/demo/jobs/timeout-redemptions?asOf=" + requestedAt(id).plus(Duration.ofHours(1)), ADMIN, null), 200);
            quiet();
        } else {
            try (AutoCloseable asleep = walletAsleep(m.id())) {
                Resp r = redeem(m.id(), reward, false);
                assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
                id = r.body().path("redemptionId").asString();
                cid = r.body().path("correlationId").asString();
                await("consumer del wallet fermo sulla richiesta", 30_000,
                        () -> count("SELECT count(*) FROM pg_locks WHERE NOT granted") > 0);
                assertThat(redemptionStatus(id)).as("wallet addormentato: la richiesta resta PENDING").isEqualTo("PENDING");
                Instant requestedAt = requestedAt(id);
                switch (row.get("caso")) {
                    case "timeout" -> ok(send("POST", "/v1/demo/jobs/timeout-redemptions?asOf="
                            + requestedAt.plus(Duration.ofMinutes(10)).plusSeconds(1), ADMIN, null), 200);
                    case "prima-del-timeout" -> ok(send("POST", "/v1/demo/jobs/timeout-redemptions?asOf="
                            + requestedAt.plus(Duration.ofMinutes(10)).minusSeconds(1), ADMIN, null), 200);
                    case "annullo-membro" -> ok(send("POST", "/v1/portal/redemptions/" + id + "/cancel?memberId=" + m.id(), null, null), 200);
                    default -> { }
                }
                String status = redemptionStatus(id);
                assertThat(status).as("stato prima del risveglio").isEqualTo(switch (row.get("caso")) {
                    case "timeout" -> "REJECTED";
                    case "annullo-membro" -> "CANCELLED";
                    default -> "PENDING";
                });
                if (!status.equals("PENDING")) {
                    assertThat(stock(reward)).as("stock ripristinato alla chiusura").isEqualTo(stockBefore);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            quiet();
        }
        String[] expected = row.get("esito").split(":");
        JsonNode done = redemption(id);
        assertThat(done.path("status").asString()).isEqualTo(expected[0]);
        if (expected.length > 1) {
            assertThat(done.path("rejectReason").asString()).isEqualTo(expected[1]);
        }
        List<Ev> chain = row.is("caso", "annullo-membro") ? assertChainIncludes(cid, row.get("catena")) : assertChain(cid, row.get("catena"));
        assertThat(pts(m.id())).as("saldo finale").isEqualTo(row.num("saldoFinale"));
        assertThat(count("SELECT count(*) FROM reward.coupon WHERE redemption_id = ?", id)).isEqualTo(row.num("coupon"));
        assertThat(stock(reward)).isEqualTo(stockBefore - (int) row.num("coupon"));
        switch (row.get("caso")) {
            case "timeout" -> {
                assertThat(ofKey(chain, "FACT:reward.redemption.rejected").get(0).data().path("reason").asString()).isEqualTo("TIMEOUT");
                Ev cancelled = ofKey(chain, "FACT:reward.redemption.cancelled").get(0);
                assertThat(cancelled.data().path("refund").asBoolean()).as("compensazione della spesa tardiva").isTrue();
                assertThat(ledger(m.id(), cid)).containsExactly("REFUND:PTS:500", "SPEND:PTS:500");
                assertThat(inbox(cid)).containsExactly("MSG-REWARD-REJECTED");
            }
            case "annullo-membro" -> {
                // TESTBOOK: ambiguo, vedi TB-E2E-SAG-004 (compensazione dopo l'annullo del membro non descritta: si verifica
                // solo l'invariante — nessun addebito netto — e lo stato finale)
                assertThat(ofKey(chain, "FACT:reward.redemption.cancelled").get(0).data().path("refund").asBoolean()).isFalse();
                long spent = ledger(m.id(), cid).stream().filter(l -> l.startsWith("SPEND")).count();
                long refunded = ledger(m.id(), cid).stream().filter(l -> l.startsWith("REFUND")).count();
                assertThat(spent).as("ogni spesa è rimborsata").isEqualTo(refunded);
            }
            default -> {
                assertThat(ledger(m.id(), cid)).containsExactly("SPEND:PTS:500");
                assertThat(inbox(cid)).containsExactly("MSG-REWARD-CONFIRMED", "MSG-REWARD-READY");
            }
        }
    }

    Instant requestedAt(String redemptionId) {
        return jdbc.sql("SELECT requested_at FROM reward.redemption WHERE id = ?").param(redemptionId)
                .query(java.sql.Timestamp.class).single().toInstant();
    }
}
