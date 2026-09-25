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
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-E2E — i percorsi dei personaggi della demo (docs/10 §2, §8) sugli scenari guidati e sul reset: criteri di
 * accettazione M1–M7 di docs/12 che attraversano più servizi, storie US-E10-03…16 e US-E11-07/09 di docs/17. Ogni caso
 * parte da un reset (lo stato dei personaggi è quello di docs/10), tranne quelli che provano il reset stesso.
 * «Oggi» è giovedì 24/09/2026: {@code @lastWeekday} = mercoledì 23, {@code @lastSaturday} = sabato 19.
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookE2ePersonasIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookE2ePersonasIT extends TestbookE2eSupportIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final String MARCO = "MBR-000002";
    private static final String GIULIA = "MBR-000003";
    private static final String CHIARA = "MBR-000007";

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

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ROME);
    }

    @TestFactory
    Stream<DynamicTest> personaggi() {
        return rows("personaggi.csv", this::personaggio);
    }

    private void personaggio(Row row) {
        String caso = row.get("caso");
        if (!caso.startsWith("reset-")) {
            resetDemo();
        }
        String cid = null;
        switch (caso) {
            case "reset-seed" -> resetDemo();
            case "acquisto-feriale", "acquisto-sabato" -> {
                LocalDate day = caso.equals("acquisto-feriale") ? today().minusDays(1)
                        : today().with(TemporalAdjusters.previous(java.time.DayOfWeek.SATURDAY));
                cid = purchase("member:" + MARCO, 130, day.atTime(10, 0).atZone(ROME).toInstant());
                quiet();
                String eventId = jdbc.sql("SELECT event_id FROM ingestion.inbound_event WHERE correlation_id = ?").param(cid)
                        .query(String.class).single();
                assertThat(cid).as("la correlazione è l'id dell'azione radice (docs/05 §2)").isEqualTo(eventId);
                assertThat(ledger(MARCO, cid).stream().filter(l -> l.endsWith("CMP-PURCHASE-BASE")).toList())
                        .isEqualTo(sorted(row.get("atteso")));
                String multiplier = caso.equals("acquisto-feriale") ? "1" : "2";
                assertThat(chain(cid).stream().filter(e -> e.key().equals("EFFECT:points.grant")
                                && e.data().path("currency").asString().equals("PTS")
                                && e.data().path("campaignCode").asString().equals("CMP-PURCHASE-BASE"))
                        .map(e -> e.data().path("campaignMultiplier").decimalValue().stripTrailingZeros().toPlainString()).toList())
                        .containsExactly(multiplier);
                assertTrace(cid, "COMPLETE");
            }
            case "bloccato" -> {
                String eventId = "tb-e2e-" + uniqueTag();
                Resp r = postEvent(eventId, "ecommerce", "purchase.completed", "member:MBR-000008", todayAt(9, 0),
                        Map.of("orderId", "ORD-TB-" + uniqueTag(), "amount", 50, "currency", "EUR"));
                assertThat(r.status()).isEqualTo(202);
                assertThat(r.body().path("status").asString() + ":" + r.body().path("rejectCode").asString()).isEqualTo(row.get("atteso"));
                quiet();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE event_id = ?", eventId)).as("niente sul topic").isZero();
                JsonNode monitor = get("/v1/inbound-events?memberId=MBR-000008&status=REJECTED");
                assertThat(monitor.toString()).contains(eventId).contains("MEMBER_NOT_ACTIVE");
            }
            case "duplicato" -> {
                JsonNode run = runScenario("SCN-DUPLICATE");
                assertThat(run.path("results").get(0).path("status").asString()).isEqualTo("ACCEPTED");
                assertThat(run.path("results").get(1).path("status").asString()).isEqualTo("DUPLICATE");
                cid = run.path("results").get(0).path("correlationId").asString();
                assertThat(ledger(MARCO, cid)).isEqualTo(sorted(row.get("atteso")));
                assertThat(count("SELECT count(*) FROM wallet.ledger_entry WHERE member_id = ? AND action_id = ?", MARCO,
                        run.path("results").get(0).path("eventId").asString())).as("un solo accredito per valuta").isEqualTo(2);
            }
            case "raffica" -> {
                JsonNode run = runScenario("SCN-WEEKEND-BURST");
                List<String> correlations = new ArrayList<>();
                for (JsonNode step : run.path("results")) {
                    assertThat(step.path("status").asString()).isEqualTo("ACCEPTED");
                    correlations.add(step.path("correlationId").asString());
                }
                assertThat(correlations).hasSize((int) row.num("atteso"));
                int evaluations = 0;
                boolean doubled = false;
                boolean limited = false;
                for (String c : correlations) {
                    for (Ev e : chain(c)) {
                        if (e.key().equals("FACT:campaign.evaluated") && e.data().path("actionType").asString().equals("purchase.completed")) {
                            evaluations++;
                            limited |= "LIMIT".equals(skipped(e).get("CMP-PURCHASE-BASE"));
                        }
                        if (e.key().equals("EFFECT:points.grant") && e.data().path("campaignMultiplier").asDouble() == 2.0) {
                            doubled = true;
                        }
                    }
                }
                assertThat(evaluations).as("una valutazione per azione, nessuna persa").isEqualTo(row.num("atteso"));
                assertThat(doubled).as("moltiplicatore del weekend").isTrue();
                assertThat(limited).as("limite 3/giorno che scatta").isTrue();
            }
            case "tier-up", "tier-up-ripetuto" -> {
                if (caso.equals("tier-up-ripetuto")) {
                    runScenario("SCN-TIER-UP");
                }
                JsonNode step = runScenario("SCN-TIER-UP").path("results").get(0);
                assertThat(step.path("status").asString()).isEqualTo("ACCEPTED");
                cid = step.path("correlationId").asString();
                if (caso.equals("tier-up")) {
                    assertThat(ledger(GIULIA, cid)).containsExactly("EARN:PTS:100:CMP-BADGE-BONUS", "EARN:PTS:162:CMP-PURCHASE-BASE",
                            "EARN:PTS:500:CMP-TIER-UP-BONUS", "EARN:STS:130:CMP-PURCHASE-BASE");
                    Ev bridged = ofKey(chain(cid), "ACTION:tier.upgraded").get(0);
                    assertThat(bridged.hop()).isEqualTo(1);
                    assertThat(get("/v1/portal/wallets/" + GIULIA).path("tier").path("code").asString()).isEqualTo("GOLD");
                    // Il livello nuovo arriva a reward: RWD-WEEKEND (GOLD, PLATINUM) non è più bloccato per Giulia (docs/10 §5).
                    assertThat(catalogEntry(GIULIA, "RWD-WEEKEND").path("lockedByTier").isNull()
                            || catalogEntry(GIULIA, "RWD-WEEKEND").path("lockedByTier").isMissingNode()).isTrue();
                    assertThat(badges(GIULIA)).contains("BDG-TRIS");
                } else {
                    assertThat(ledger(GIULIA, cid)).containsExactly("EARN:PTS:195:CMP-PURCHASE-BASE", "EARN:STS:130:CMP-PURCHASE-BASE");
                    assertThat(count("SELECT count(*) FROM wallet.tier_history WHERE member_id = ? AND kind = 'UPGRADE' AND to_tier = 'GOLD'",
                            GIULIA)).as("una sola salita nelle due esecuzioni").isEqualTo(1);
                }
            }
            case "referral" -> {
                JsonNode step = runScenario("SCN-REFERRAL").path("results").get(0);
                cid = step.path("correlationId").asString();
                assertThat(ledger(MARCO, cid)).containsExactly("EARN:PTS:625:CMP-REFERRAL-REFERRER", "EARN:STS:250:CMP-REFERRAL-REFERRER");
                assertThat(get("/v1/portal/members/" + MARCO + "/referral").path("completedCount").asInt()).isEqualTo(1);
            }
            case "onboarding", "onboarding-ripetuto" -> {
                if (caso.equals("onboarding-ripetuto")) {
                    runScenario("SCN-ONBOARDING");
                }
                JsonNode run = runScenario("SCN-ONBOARDING");
                JsonNode results = run.path("results");
                String login = results.get(0).path("correlationId").asString();
                String profile = results.get(1).path("correlationId").asString();
                cid = results.get(2).path("correlationId").asString();
                if (caso.equals("onboarding")) {
                    assertChain(login, "A:app.login.daily=1 F:campaign.evaluated=1 E:points.grant=1 F:wallet.points.earned=1 F:message.delivered=1");
                    assertChain(profile, "A:member.profile.completed=1 F:campaign.evaluated=1 E:points.grant=1 F:wallet.points.earned=1 F:message.delivered=1");
                    assertThat(ledger("MBR-000001", login)).containsExactly("EARN:PTS:5:CMP-APP-DAILY");
                    assertThat(ledger("MBR-000001", profile)).containsExactly("EARN:PTS:150:CMP-PROFILE");
                    assertThat(badges("MBR-000001")).containsExactly("BDG-FIRST");
                } else {
                    assertThat(skipped(ofKey(assertChain(login, "A:app.login.daily=1 F:campaign.evaluated=1"), "FACT:campaign.evaluated").get(0)))
                            .containsEntry("CMP-APP-DAILY", "LIMIT");
                    assertThat(skipped(ofKey(assertChain(profile, "A:member.profile.completed=1 F:campaign.evaluated=1"),
                            "FACT:campaign.evaluated").get(0))).containsEntry("CMP-PROFILE", "LIMIT");
                }
                JsonNode profileView = get("/v1/portal/members/MBR-000001");
                assertThat(profileView.path("completeness").path("completed").asBoolean(profileView.path("completed").asBoolean()))
                        .as("profilo di Anna ancora incompleto (Q-63)").isFalse();
            }
            case "digitale" -> {
                JsonNode run = runScenario("SCN-DIGITAL");
                String ebill = run.path("results").get(0).path("correlationId").asString();
                cid = run.path("results").get(1).path("correlationId").asString();
                assertChain(ebill, "A:ebill.activated=1 F:campaign.evaluated=1 E:points.grant=2 F:wallet.points.earned=2 F:member.updated=1 F:message.delivered=1");
                assertThat(ledger(MARCO, ebill)).containsExactly("EARN:PTS:375:CMP-EBILL", "EARN:STS:150:CMP-EBILL");
                assertThat(ledger(MARCO, cid)).containsExactly("EARN:PTS:100:CMP-BADGE-BONUS", "EARN:PTS:500:CMP-DIRECT-DEBIT",
                        "EARN:STS:200:CMP-DIRECT-DEBIT");
                ok(send("POST", "/v1/demo/jobs/refresh-segments", ADMIN, null), 200);
                quiet();
                String grid = send("GET", "/v1/portal/content?memberId=" + MARCO + "&placement=HOME_GRID", null, null).body().toString();
                assertThat(grid).contains("CNT-DIGITAL-THANKS").doesNotContain("CNT-EBILL");
            }
            case "smoke", "smoke-ripetuto" -> {
                if (caso.equals("smoke-ripetuto")) {
                    runScenario("SCN-SMOKE");
                }
                long started = System.nanoTime();
                JsonNode step = runScenario("SCN-SMOKE").path("results").get(0);
                cid = step.path("correlationId").asString();
                if (caso.equals("smoke")) {
                    await("+5 PTS a Marco", 15_000, () -> pts(MARCO) == 1855);
                    assertThat((System.nanoTime() - started) / 1_000_000).as("entro 15 s (docs/12 §4)").isLessThanOrEqualTo(15_000);
                } else {
                    assertThat(skipped(ofKey(chain(cid), "FACT:campaign.evaluated").get(0))).containsEntry("CMP-APP-DAILY", "LIMIT");
                }
            }
            case "davide" -> {
                int stockBefore = stock("RWD-SHOP-10");
                Resp r = redeem("MBR-000004", "RWD-SHOP-10", false);
                assertThat(r.status()).isEqualTo(202);
                String id = r.body().path("redemptionId").asString();
                cid = r.body().path("correlationId").asString();
                await("FULFILLED entro 10 s", 10_000, () -> redemptionStatus(id).equals("FULFILLED"));
                assertThat(redemption(id).path("couponCode").asString()).matches("SHP10-[A-Z2-9]{4}-[A-Z2-9]{4}");
                quiet();
                assertThat(stock("RWD-SHOP-10")).isEqualTo(stockBefore - 1);
            }
            case "matteo" -> {
                long matured = count("""
                        SELECT count(*) FROM gamification.winning_instant w JOIN gamification.prize p ON p.id = w.prize_id
                        WHERE p.contest_id = (SELECT id FROM gamification.contest WHERE code = 'IW-AUTUNNO')
                          AND w.status = 'OPEN' AND w.instant_at <= ? AND p.code <> 'PTS-50'
                        """, java.sql.Timestamp.from(clock.instant()));
                assertThat(matured).as("precondizione: istanti maturi di altri premi nel seed (docs/10 §6)").isPositive();
                JsonNode win = plantAndPlay("MBR-000010", "PTS-50");
                assertThat(win.path("outcome").asString()).isEqualTo("WIN");
                assertThat(win.path("prize").path("code").asString()).as("vince il premio piantato (Q-62)").isEqualTo("PTS-50");
                cid = win.path("correlationId").asString();
                String c = cid;
                await("catena fino a wallet.points.earned entro 10 s", 10_000,
                        () -> chain(c).stream().anyMatch(e -> e.key().equals("FACT:wallet.points.earned")));
                quiet();
            }
            case "preavviso", "preavviso-ripetuto" -> {
                long inboxBefore = count("SELECT count(*) FROM engagement.inbox_message WHERE member_id = ? AND template_code = 'MSG-POINTS-EXPIRING'", CHIARA);
                int runs = caso.equals("preavviso") ? 1 : 2;
                for (int i = 0; i < runs; i++) {
                    ok(send("POST", "/v1/demo/jobs/expiry-warnings?asOf=" + today(), ADMIN, null), 200);
                    quiet();
                }
                List<Map<String, Object>> facts = jdbc.sql("""
                        SELECT (payload->'data'->>'amount')::bigint AS amount FROM insight.event_store
                        WHERE member_id = ? AND short_type = 'wallet.points.expiring'
                        """).param(CHIARA).query().listOfRows();
                assertThat(facts).as("un preavviso per lotto").hasSize(1);
                assertThat(((Number) facts.get(0).get("amount")).longValue()).isEqualTo(row.num("atteso"));
                assertThat(count("SELECT count(*) FROM engagement.inbox_message WHERE member_id = ? AND template_code = 'MSG-POINTS-EXPIRING'", CHIARA))
                        .isEqualTo(inboxBefore + 1);
            }
            case "scadenza-prima", "scadenza-giorno", "scadenza-31" -> {
                List<Integer> offsets = switch (caso) {
                    case "scadenza-prima" -> List.of(11);
                    case "scadenza-giorno" -> List.of(11, 12);
                    default -> List.of(31);
                };
                for (int d : offsets) {
                    ok(send("POST", "/v1/demo/jobs/expire-points?asOf=" + today().plusDays(d), ADMIN, null), 200);
                    quiet();
                }
                List<String> expired = jdbc.sql("SELECT currency || ':' || amount FROM wallet.ledger_entry WHERE member_id = ? AND type = 'EXPIRE'")
                        .param(CHIARA).query(String.class).list();
                if (caso.equals("scadenza-prima")) {
                    assertThat(expired).isEmpty();
                } else {
                    assertThat(expired).as("un solo EXPIRE per membro e valuta").containsExactly("PTS:1900");
                    List<Map<String, Object>> facts = jdbc.sql("""
                            SELECT (payload->'data'->>'amount')::bigint AS amount, (payload->'data'->>'balanceAfter')::bigint AS after
                            FROM insight.event_store WHERE member_id = ? AND short_type = 'wallet.points.expired'
                            """).param(CHIARA).query().listOfRows();
                    assertThat(facts).hasSize(1);
                    assertThat(((Number) facts.get(0).get("amount")).longValue()).isEqualTo(1900);
                    assertThat(((Number) facts.get(0).get("after")).longValue()).isEqualTo(500);
                    assertThat(jdbc.sql("SELECT balance_pts FROM member.member_projection WHERE member_id = ?").param(CHIARA)
                            .query(Long.class).single()).as("proiezione del member-service").isEqualTo(500);
                }
            }
            case "reset-doppio" -> {
                resetDemo();
                Map<String, List<String>> first = seedFingerprint();
                resetDemo();
                assertThat(seedFingerprint()).isEqualTo(first);
            }
            case "reset-tra-esecuzioni" -> {
                resetDemo();
                List<Object> first = tierUpOutcome();
                resetDemo();
                assertPersona(row.get("atteso"));
                assertThat(tierUpOutcome()).isEqualTo(first);
            }
            case "reset-membro-nuovo" -> {
                Member m = newMember();
                resetDemo();
                assertThat(send("GET", "/v1/members/" + m.id(), ADMIN, null).status()).isEqualTo(404);
                List<String> leftovers = new ArrayList<>();
                for (String table : List.of("member.member:id", "wallet.wallet:member_id", "wallet.member_tier:member_id",
                        "wallet.ledger_entry:member_id", "ingestion.member_index:member_id", "campaign.member_snapshot:member_id",
                        "reward.reward_member_snapshot:member_id", "gamification.gamification_member_snapshot:member_id",
                        "gamification.leaderboard_score:member_id", "engagement.engagement_member_snapshot:member_id",
                        "engagement.inbox_message:member_id")) {
                    String[] t = table.split(":");
                    if (count("SELECT count(*) FROM " + t[0] + " WHERE " + t[1] + " = ?", m.id()) > 0) {
                        leftovers.add(t[0]);
                    }
                }
                assertThat(leftovers).as("tabelle che conservano il membro nuovo dopo il reset").isEmpty();
            }
            case "reset-ruolo" -> {
                Member m = newMember();
                assertThat(send("POST", "/v1/demo/reset", MARKETING, null).status()).isEqualTo(403);
                assertThat(send("POST", "/v1/demo/reset", null, null).status()).isEqualTo(403);
                quiet();
                assertThat(send("GET", "/v1/members/" + m.id(), ADMIN, null).status()).isEqualTo(200);
                assertThat(pts(m.id())).isEqualTo(100);
            }
            default -> throw new IllegalArgumentException(caso);
        }
        if (!row.blank("catena")) {
            assertChain(cid, row.get("catena"));
            assertTrace(cid, "COMPLETE");
        }
        if (!row.blank("messaggi")) {
            assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
        }
        if (row.get("atteso").contains("=") && !caso.equals("reset-tra-esecuzioni")) {
            assertPersona(row.get("atteso"));
        }
    }

    /** {@code MBR-x=STATO/LIVELLO/PTS/STS …}: stato del membro (member) e wallet (portale). */
    private void assertPersona(String spec) {
        for (String token : spec.trim().split("\\s+")) {
            String[] kv = token.split("=");
            String[] v = kv[1].split("/");
            JsonNode wallet = get("/v1/portal/wallets/" + kv[0]);
            String actual = get("/v1/members/" + kv[0]).path("status").asString() + "/" + wallet.path("tier").path("code").asString()
                    + "/" + wallet.path("balances").path("PTS").path("active").asLong() + "/" + wallet.path("tier").path("periodSts").asLong();
            assertThat(actual).as("stato di %s", kv[0]).isEqualTo(String.join("/", v));
        }
    }

    private JsonNode catalogEntry(String memberId, String rewardCode) {
        for (JsonNode band : get("/v1/portal/catalog?memberId=" + memberId).path("bands")) {
            for (JsonNode r : band.path("rewards")) {
                if (rewardCode.equals(r.path("code").asString())) {
                    return r;
                }
            }
        }
        throw new AssertionError("premio assente dal catalogo: " + rewardCode);
    }

    /** Esito di SCN-TIER-UP: movimenti del tracciato, catena e saldo finale di Giulia. */
    private List<Object> tierUpOutcome() {
        JsonNode step = runScenario("SCN-TIER-UP").path("results").get(0);
        String cid = step.path("correlationId").asString();
        return List.of(ledger(GIULIA, cid), counts(chain(cid)), pts(GIULIA), sts(GIULIA), tier(GIULIA), inbox(cid));
    }

    /** Stato del seed che deve ripetersi identico a ogni reset (docs/10 §1.3: semi fissi). */
    private Map<String, List<String>> seedFingerprint() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("coupon", jdbc.sql("SELECT code || '|' || status || '|' || coalesce(member_id, '-') FROM reward.coupon ORDER BY code")
                .query(String.class).list());
        out.put("istanti", jdbc.sql("""
                        SELECT p.code || '|' || w.instant_at || '|' || w.status FROM gamification.winning_instant w
                        JOIN gamification.prize p ON p.id = w.prize_id ORDER BY 1
                        """).query(String.class).list());
        out.put("wallet", jdbc.sql("SELECT member_id || '|' || currency || '|' || balance_active FROM wallet.wallet ORDER BY 1")
                .query(String.class).list());
        out.put("membri", jdbc.sql("SELECT id || '|' || status FROM member.member ORDER BY 1").query(String.class).list());
        out.put("richieste", jdbc.sql("SELECT member_id || '|' || reward_code || '|' || status FROM reward.redemption ORDER BY 1")
                .query(String.class).list());
        return out;
    }
}
