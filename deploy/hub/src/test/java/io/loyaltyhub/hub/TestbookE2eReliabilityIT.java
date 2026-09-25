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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-E2E — affidabilità dei percorsi: riconsegna di un messaggio a metà catena (RED, docs/04 §5, RNF-03),
 * handler che fallisce una o più volte e poi riprova o va in DLQ (FAIL, docs/04 §5, docs/12 M0, Q-131), due azioni
 * ravvicinate dello stesso membro (CON, RNF-04, docs/03 §3.5 contatori atomici, wallet §5 lock di riga).
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookE2eReliabilityIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookE2eReliabilityIT extends TestbookE2eSupportIT {

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

    Instant weekday() {
        return todayAt(9, 0);
    }

    // ---------- RED: riconsegna ----------

    /** Un percorso eseguito: il tracciato e i membri che tocca. */
    record Journey(String correlationId, List<String> members) {
    }

    Journey journey(String kind) {
        switch (kind) {
            case "acquisto1000" -> {
                Member m = newMember();
                String cid = purchase(m.subject(), 1000, weekday());
                quiet();
                return new Journey(cid, List.of(m.id()));
            }
            case "premio" -> {
                Member m = newMember();
                credit(m.id(), 400);
                Resp r = redeem(m.id(), "RWD-COFFEE-5", false);
                quiet();
                assertThat(redemptionStatus(r.body().path("redemptionId").asString())).isEqualTo("FULFILLED");
                return new Journey(r.body().path("correlationId").asString(), List.of(m.id()));
            }
            case "annullo" -> {
                Member m = newMember();
                credit(m.id(), 1400);
                Resp r = redeem(m.id(), "RWD-BORRACCIA", true);
                quiet();
                String id = r.body().path("redemptionId").asString();
                ok(send("POST", "/v1/redemptions/" + id + "/cancel", CARE, Map.of("reason", "Articolo danneggiato")), 200);
                quiet();
                assertThat(pts(m.id())).isEqualTo(1500);
                return new Journey(r.body().path("correlationId").asString(), List.of(m.id()));
            }
            case "vincita-coupon" -> {
                onlyPlantedInstants();
                Member m = newMember();
                JsonNode play = plantAndPlay(m.id(), "COFFEE");
                assertThat(play.path("outcome").asString()).isEqualTo("WIN");
                quiet();
                return new Journey(play.path("correlationId").asString(), List.of(m.id()));
            }
            case "amico" -> {
                Member referrer = newMember();
                Member invitee = newMember(referrer.referralCode());
                String cid = purchase(invitee.subject(), 60, weekday());
                quiet();
                return new Journey(cid, List.of(referrer.id(), invitee.id()));
            }
            case "iscrizione" -> {
                Member m = newMember();
                return new Journey(rootCorrelation(m.id(), "FACT", "member.registered"), List.of(m.id()));
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }

    @TestFactory
    Stream<DynamicTest> riconsegna() {
        return rows("riconsegna.csv", this::riconsegna);
    }

    private void riconsegna(Row row) {
        Journey j = journey(row.get("percorso"));
        String[] members = j.members().toArray(String[]::new);
        Map<String, List<String>> before = memberState(members);
        List<Ev> chainBefore = chain(j.correlationId());
        long dlqBefore = count("SELECT count(*) FROM insight.dlq_entry");
        List<String> redelivered = new ArrayList<>();
        if (row.is("evento", "*")) {
            chainBefore.forEach(e -> redelivered.add(e.id()));
        } else {
            redelivered.add(pick(chainBefore, row.get("evento"), row.get("filtro")).id());
        }
        String newId = row.is("nuovoId", "si") ? "tb-e2e-redelivery-" + uniqueTag() : null;
        for (String id : redelivered) {
            redeliver(id, newId);
        }
        quiet();
        assertThat(memberState(members)).as("la riconsegna non cambia lo stato di nessun servizio").isEqualTo(before);
        List<Ev> chainAfter = chain(j.correlationId());
        if (newId == null) {
            assertThat(counts(chainAfter)).as("nessun nuovo messaggio a valle").isEqualTo(counts(chainBefore));
        } else {
            Map<String, Integer> expected = new TreeMap<>(counts(chainBefore));
            expected.merge(pick(chainBefore, row.get("evento"), row.get("filtro")).key(), 1, Integer::sum);
            assertThat(counts(chainAfter)).as("solo la copia con il nuovo id, nessun effetto a valle").isEqualTo(expected);
        }
        assertThat(count("SELECT count(*) FROM insight.dlq_entry")).as("nessuna voce DLQ").isEqualTo(dlqBefore);
    }

    /** Primo evento del tracciato con quella chiave FAMIGLIA:tipo e, se indicato, {@code data.campo=valore}. */
    static Ev pick(List<Ev> chain, String key, String filter) {
        return chain.stream().filter(e -> e.key().equals(key)).filter(e -> {
            if (filter == null || filter.isBlank() || filter.equals("-")) {
                return true;
            }
            String[] kv = filter.split("=");
            return kv[1].equals(e.data().path(kv[0]).asString());
        }).findFirst().orElseThrow(() -> new AssertionError("nessun " + key + " " + filter + " nel tracciato"));
    }

    // ---------- FAIL: handler che fallisce e riprova ----------

    @TestFactory
    Stream<DynamicTest> guasti() {
        return rows("guasti.csv", this::guasti);
    }

    private void guasti(Row row) {
        Member m = newMember();
        long dlqBefore = count("SELECT count(*) FROM insight.dlq_entry");
        int times = (int) row.num("guasti");
        String failure = switch (row.get("caso")) {
            case "wallet" -> failInserts("wallet.ledger_entry", "NEW.member_id = '" + m.id() + "'", times);
            case "ponte" -> failInserts("ingestion.inbound_event", "NEW.member_id = '" + m.id()
                    + "' AND NEW.origin = 'INTERNAL' AND NEW.type_code = 'tier.upgraded'", times);
            default -> null;
        };
        String cid;
        try {
            cid = switch (row.get("caso")) {
                case "wallet" -> purchase(m.subject(), 200, weekday());
                case "ponte" -> purchase(m.subject(), 1000, weekday());
                default -> act("app", "app.login.daily", m.subject(), weekday(), Map.of("platform", "WEB", "_poison", true));
            };
            quiet();
        } finally {
            if (failure != null) {
                dropFailure(failure);
            }
        }
        assertChain(cid, row.get("catena"));
        assertThat(pts(m.id())).isEqualTo(row.num("ptsAttesi"));
        assertThat(sts(m.id())).isEqualTo(row.num("stsAttesi"));
        if (row.blank("dlq")) {
            assertThat(count("SELECT count(*) FROM insight.dlq_entry")).as("nessuna voce DLQ").isEqualTo(dlqBefore);
            assertTrace(cid, "COMPLETE");
            return;
        }
        String[] dlq = row.get("dlq").split(":");
        List<Map<String, Object>> entries = jdbc.sql("""
                        SELECT consumer, attempts, original_type, error_code FROM insight.dlq_entry
                        WHERE payload->>'lhcorrelationid' = ? OR event_id IN (SELECT event_id FROM insight.event_store WHERE correlation_id = ?)
                        """).params(cid, cid).query().listOfRows();
        assertThat(entries).as("una voce DLQ per il tracciato").hasSize(1);
        Map<String, Object> e = entries.get(0);
        assertThat(e.get("consumer")).isEqualTo(dlq[0]);
        assertThat(((Number) e.get("attempts")).intValue()).as("tentativi").isEqualTo(Integer.parseInt(dlq[1]));
        assertThat(e.get("original_type")).isEqualTo(dlq[2]);
        assertThat(String.valueOf(e.get("error_code"))).as("codice d'errore").isNotBlank().isNotEqualTo("null");
        if (row.is("caso", "veleno")) {
            assertThat(e.get("error_code")).isEqualTo("DEMO_POISON");
            // Gli altri consumer della stessa azione proseguono (member: statistiche di attività).
            assertThat(count("SELECT count(*) FROM member.member_stats WHERE member_id = ? AND actions_total >= 1", m.id())).isEqualTo(1);
        }
        assertTrace(cid, "FAILED");
    }

    // ---------- CON: due azioni ravvicinate dello stesso membro ----------

    @TestFactory
    Stream<DynamicTest> concorrenza() {
        return rows("concorrenza.csv", this::concorrenza);
    }

    private void concorrenza(Row row) {
        Member m = newMember();
        List<Callable<String>> calls = new ArrayList<>();
        List<String> redemptionIds = new ArrayList<>();
        switch (row.get("caso")) {
            case "salita" -> {
                for (int i = 0; i < 2; i++) {
                    calls.add(() -> purchase(m.subject(), 600, weekday()));
                }
            }
            case "limite" -> {
                for (int i = 0; i < 4; i++) {
                    calls.add(() -> purchase(m.subject(), 10, weekday()));
                }
            }
            case "saldo" -> {
                credit(m.id(), 400);
                for (int i = 0; i < 2; i++) {
                    calls.add(() -> {
                        Resp r = redeem(m.id(), "RWD-COFFEE-5", false);
                        assertThat(r.status()).isEqualTo(202);
                        synchronized (redemptionIds) {
                            redemptionIds.add(r.body().path("redemptionId").asString());
                        }
                        return r.body().path("correlationId").asString();
                    });
                }
            }
            case "digitale" -> {
                calls.add(() -> act("billing", "ebill.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag())));
                calls.add(() -> act("billing", "directdebit.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag())));
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
        List<String> correlations = parallel(calls);
        quiet();
        Map<String, Integer> union = new TreeMap<>();
        for (String cid : correlations) {
            List<Ev> chain = chain(cid);
            assertTree(chain);
            counts(chain).forEach((k, n) -> union.merge(k, n, Integer::sum));
        }
        assertThat(union).as("catene dei due tracciati, sommate").isEqualTo(parseChain(row.get("unione")));
        assertThat(pts(m.id())).isEqualTo(row.num("ptsAttesi"));
        assertThat(sts(m.id())).isEqualTo(row.num("stsAttesi"));
        assertThat(tier(m.id())).isEqualTo(row.get("livello"));
        switch (row.get("caso")) {
            case "salita" -> {
                assertThat(count("SELECT count(*) FROM wallet.tier_history WHERE member_id = ? AND kind = 'UPGRADE'", m.id())).isEqualTo(1);
                assertThat(badges(m.id())).containsExactly("BDG-FIRST", "BDG-SPENDER");
            }
            case "limite" -> {
                Map<String, Integer> outcomes = new HashMap<>();
                for (String cid : correlations) {
                    for (Ev ev : chain(cid)) {
                        if (ev.key().equals("FACT:campaign.evaluated") && ev.data().path("actionType").asString().equals("purchase.completed")) {
                            if (matched(ev).contains("CMP-PURCHASE-BASE")) {
                                outcomes.merge("MATCH", 1, Integer::sum);
                            }
                            String reason = skipped(ev).get("CMP-PURCHASE-BASE");
                            if (reason != null) {
                                outcomes.merge(reason, 1, Integer::sum);
                            }
                        }
                    }
                }
                assertThat(outcomes).isEqualTo(Map.of("MATCH", 3, "LIMIT", 1));
                assertThat(badges(m.id())).containsExactly("BDG-FIRST", "BDG-TRIS");
            }
            case "saldo" -> {
                List<String> statuses = redemptionIds.stream().map(this::redemptionStatus).sorted().toList();
                assertThat(statuses).containsExactly("FULFILLED", "REJECTED");
                assertThat(ledgerAll(m.id()).stream().filter(l -> l.startsWith("SPEND"))).containsExactly("SPEND:PTS:500");
            }
            case "digitale" -> assertThat(badges(m.id())).containsExactly("BDG-DIGITAL");
            default -> { }
        }
    }

    private static List<String> parallel(List<Callable<String>> calls) {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> c : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
