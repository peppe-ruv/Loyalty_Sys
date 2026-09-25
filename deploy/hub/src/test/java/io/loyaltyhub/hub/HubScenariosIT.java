package io.loyaltyhub.hub;

import io.loyaltyhub.hub.bus.HubInProcessBus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Accettazione M2 (docs/12 §M2) e scenari guidati (docs/10 §8): <strong>ogni</strong> scenario di
 * {@code seed/scenarios.json} gira end to end nel deployable consolidato e produce ciò che docs/10 §8 e i campi
 * {@code expect}/{@code watch} dello scenario descrivono.
 *
 * <p>Per ogni esecuzione: stato {@code RUNNING → DONE}; esito di ingresso di ogni passo ({@code ACCEPTED},
 * {@code DUPLICATE}, {@code REJECTED} col codice, {@code UNMATCHED}) sia nei risultati della run sia nel monitor
 * ingressi ({@code /v1/inbound-events}); per i passi accettati, i movimenti {@code EARN} attribuiti alla campagna
 * giusta (mai i delta del saldo: più campagne possono scattare sulla stessa azione e il moltiplicatore di livello
 * arrotonda per difetto). Gli importi attesi sono calcolati da {@code seed/campaigns.json} e {@code seed/tiers.json}
 * con le regole di docs/03 §3 (PER_AMOUNT, MULTIPLIER, tier), mai copiati da un'esecuzione. Su ogni run: un solo
 * albero per passo (tracciati di insight: una radice, tutti i nodi collegati, nessun'altra correlazione nata dopo
 * l'avvio) e nessuna voce DLQ inattesa (una sola, e solo per SCN-POISON).
 *
 * <p>I due punti espliciti di M2: SCN-WEEKEND-BURST (12 azioni ACCEPTED, 12 valutazioni nell'event store, attese in
 * polling) e SCN-DUPLICATE (secondo invio DUPLICATE, un solo accredito della campagna dopo l'avvio).
 *
 * <p>Ordine e reset: alcuni scenari consumano gli stessi limiti "1 / sempre" o "1 / giorno" (CMP-EBILL di Marco in
 * SCN-MIXED-DAY e SCN-DIGITAL, l'accesso quotidiano e il primo acquisto di Anna in SCN-WEEKEND-ANNA e
 * SCN-ONBOARDING). Ciascuno descrive la demo appena accesa, quindi girano in due fasi separate da
 * {@code POST /v1/demo/reset} (docs/12: "i 12 membri tornano allo stato di docs/10"). Ogni scenario gira una volta.
 *
 * <p>I job di sfondo che cambiano lo stato da soli sono spenti (consegna webhook, timeout delle richieste premio,
 * retention di insight, riannuncio differito dei segmenti); {@code loyaltyhub.jobs.enabled} è già falso in hub.yml.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hub",
                "loyaltyhub.webhooks.dispatcher.enabled=false",
                "loyaltyhub.reward.redemption-timeout.enabled=false",
                "loyaltyhub.insight.retention.cron=-",
                "loyaltyhub.member.segments.reannounce-delay-ms=0"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubScenariosIT {

    private static final EmbeddedPostgres PG = startPg();

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final String ADMIN = "ADMIN:marta.admin";
    private static final String INTERNAL_SOURCE = "urn:loyaltyhub:source:internal";

    /** Fase 1 (demo appena accesa): scenari che non si pestano i piedi. */
    private static final List<String> PHASE_1 = List.of("SCN-WEEKEND-ANNA", "SCN-MIXED-DAY", "SCN-REJECTS", "SCN-BAD-EVENT");
    /** Fase 2 (dopo il reset): gli scenari di docs/10 §8 che condividono membri e limiti con la fase 1. */
    private static final List<String> PHASE_2 = List.of("SCN-ONBOARDING", "SCN-TIER-UP", "SCN-REFERRAL", "SCN-DIGITAL",
            "SCN-WEEKEND-BURST", "SCN-DUPLICATE", "SCN-SMOKE", "SCN-POISON");

    private static final long RUN_TIMEOUT_MS = 60_000;
    private static final long EFFECT_TIMEOUT_MS = 30_000;
    /** Quiete: 2 giri del relay dell'outbox (500 ms) senza scritture. */
    private static final long QUIET_WINDOW_MS = 1_000;
    private static final String BARRIER_TOPIC = "lh.test.scenarios-barrier";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private HubInProcessBus bus;

    private final Map<String, CountDownLatch> barriers = new ConcurrentHashMap<>();

    private Map<String, JsonNode> scenarios;
    private Map<String, JsonNode> campaigns;
    private Map<String, JsonNode> tiers;
    private List<String> memberIds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void everyDemoScenarioRunsEndToEnd() {
        long started = System.nanoTime();
        loadSeed();
        Set<String> planned = new LinkedHashSet<>(PHASE_1);
        planned.addAll(PHASE_2);
        assertThat(planned).as("ogni scenario di seed/scenarios.json è eseguito (uno nuovo va aggiunto a questo test)")
                .containsExactlyInAnyOrderElementsOf(scenarios.keySet());

        bus.subscribe(BARRIER_TOPIC, "lh-test-barrier", r -> {
            CountDownLatch latch = barriers.remove(r.key());
            if (latch != null) {
                latch.countDown();
            }
        });
        awaitQuiescence("avvio");

        List<Result> results = new ArrayList<>();
        for (String code : PHASE_1) {
            results.add(runAndVerify(code));
        }
        JsonNode reset = client().post().uri("/v1/demo/reset").header("X-LH-Actor", ADMIN)
                .retrieve().body(JsonNode.class);
        assertThat(reset.path("status").asString()).isEqualTo("OK");
        awaitQuiescence("reset");
        for (String code : PHASE_2) {
            results.add(runAndVerify(code));
        }

        StringBuilder report = new StringBuilder("\n=== Scenari demo (HubScenariosIT) ===\n");
        report.append(String.format("%-18s %-6s %-5s %-38s %s%n", "scenario", "esito", "s", "ingressi", "effetti verificati"));
        for (Result r : results) {
            report.append(String.format("%-18s %-6s %5.1f %-38s %s%n", r.code(), r.error() == null ? "OK" : "FAIL",
                    r.seconds(), r.outcomes(), r.error() == null ? r.effects() : r.error().split("\n")[0]));
        }
        report.append(String.format("durata totale: %.1f s%n", (System.nanoTime() - started) / 1e9));
        System.out.println(report);

        List<String> failures = results.stream().filter(r -> r.error() != null)
                .map(r -> r.code() + ":\n" + r.error()).toList();
        if (!failures.isEmpty()) {
            fail("Scenari che non fanno ciò che docs/10 §8 descrive:\n\n" + String.join("\n\n", failures));
        }
    }

    // =====================================================================================================
    // Esecuzione e verifiche comuni
    // =====================================================================================================

    private Result runAndVerify(String code) {
        long t0 = System.nanoTime();
        Run run = new Run(code, scenarios.get(code));
        try {
            // Fotografie prima dell'avvio: movimenti EARN già esistenti, livelli, DLQ.
            Instant ledgerFrom = Instant.now().minus(Duration.ofDays(9));
            for (String m : memberIds) {
                Set<String> ids = new HashSet<>();
                earnEntries(m, ledgerFrom).forEach(e -> ids.add(e.path("id").asString()));
                run.earnBefore.put(m, ids);
                run.tierBefore.put(m, wallet(m).path("tier"));
            }
            run.ledgerFrom = ledgerFrom;
            run.dlqBefore = dlqTotal();
            prepare(run);

            run.start = Instant.now();
            JsonNode started = client().post().uri("/v1/demo/scenarios/" + code + "/run").header("X-LH-Actor", ADMIN)
                    .retrieve().body(JsonNode.class);
            run.runId = started.path("runId").asString();
            assertThat(run.runId).as("runId").isNotBlank();

            JsonNode done = awaitRun(run.runId);
            assertThat(done.path("status").asString()).as("stato finale della run %s", run.runId).isEqualTo("DONE");
            JsonNode stepResults = done.path("results");
            int total = run.spec.path("steps").size();
            assertThat(done.path("stepsTotal").asInt()).isEqualTo(total);
            assertThat(done.path("stepsDone").asInt()).isEqualTo(total);
            assertThat(stepResults.size()).isEqualTo(total);

            for (int i = 0; i < total; i++) {
                JsonNode spec = run.spec.path("steps").get(i);
                JsonNode res = stepResults.get(i);
                String expected = spec.path("expect").asString("ACCEPTED");
                Step step = new Step(i, spec, spec.path("memberId").asString(), spec.path("type").asString(), expected,
                        res.path("eventId").asString(), res.path("correlationId").asString(),
                        res.path("status").asString(), res.path("rejectCode").asString(null));
                assertThat(step.status()).as("%s passo %d (%s): esito d'ingresso", code, i + 1, spec.path("note").asString())
                        .isEqualTo(expected);
                assertThat(res.path("ok").asBoolean()).as("%s passo %d: ok nella run", code, i + 1).isTrue();
                run.steps.add(step);
            }
            verifyInboundMonitor(run);

            // Effetti descritti dallo scenario (attese in polling), poi quiete e verifiche esatte.
            verifyScenario(run);
            awaitEarn(run);
            awaitQuiescence(code);
            run.checksAfterQuiet.forEach(Runnable::run);
            verifyEarnExactly(run);
            verifyNoEffectFor(run);
            verifyOneTreePerStep(run);
            verifyDlq(run);
            afterChecks(run);

            return new Result(code, (System.nanoTime() - t0) / 1e9, run.outcomes(), run.effectSummary(), null);
        } catch (AssertionError | RuntimeException e) {
            try {
                awaitQuiescence(code + " (dopo errore)");
            } catch (AssertionError ignored) {
                // già in errore: il resoconto riporta il primo
            }
            return new Result(code, (System.nanoTime() - t0) / 1e9, run.outcomes(), run.effectSummary(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Monitor ingressi (BO-26): per ogni {@code eventId} le righe di {@code /v1/inbound-events} hanno gli esiti dei passi
     * che lo usano (SCN-DUPLICATE: ACCEPTED + DUPLICATE) e lo stesso codice di rifiuto; il dettaglio dà l'istante di
     * business del passo.
     */
    private void verifyInboundMonitor(Run run) {
        Map<String, List<Step>> byEventId = new LinkedHashMap<>();
        run.steps.forEach(s -> byEventId.computeIfAbsent(s.eventId(), k -> new ArrayList<>()).add(s));
        for (Map.Entry<String, List<Step>> e : byEventId.entrySet()) {
            List<JsonNode> rows = new ArrayList<>();
            for (JsonNode r : get("/v1/inbound-events?q={q}&limit=50", e.getKey())) {
                if (e.getKey().equals(r.path("eventId").asString()) && r.path("receivedAt").asString().compareTo(
                        run.start.minusSeconds(1).toString()) >= 0) {
                    rows.add(r);
                }
            }
            List<String> expected = e.getValue().stream().map(Step::expected).sorted().toList();
            List<String> actual = rows.stream().map(r -> r.path("status").asString()).sorted().toList();
            assertThat(actual).as("%s: esiti nel monitor ingressi per l'evento %s", run.code, e.getKey())
                    .isEqualTo(expected);
            for (Step s : e.getValue()) {
                JsonNode row = rows.stream().filter(r -> r.path("status").asString().equals(s.status())).findFirst()
                        .orElseThrow();
                assertThat(row.path("rejectCode").asString(null)).as("%s passo %d: codice di rifiuto nel monitor",
                        run.code, s.index() + 1).isEqualTo(s.rejectCode());
                JsonNode detail = get("/v1/inbound-events/{id}", row.path("id").asString());
                run.businessTime.put(s.index(), Instant.parse(detail.path("eventTime").asString()));
            }
        }
    }

    /** Movimenti EARN attesi: almeno quelli (in polling), prima della quiete. */
    private void awaitEarn(Run run) {
        await(run.code + ": movimenti EARN attesi " + run.expected, EFFECT_TIMEOUT_MS, () -> {
            for (Map.Entry<String, List<Expected>> e : run.expectedByMemberCampaign().entrySet()) {
                String[] k = e.getKey().split(" ");
                if (newEarn(run, k[0], k[1]).size() < e.getValue().size()) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * A riposo, per ogni coppia (membro, campagna) attesa i nuovi movimenti EARN sono <em>esattamente</em> quelli
     * attesi: stessa valuta, stesso importo e, per le campagne scattate sul passo, la stessa azione.
     */
    private void verifyEarnExactly(Run run) {
        for (Map.Entry<String, List<Expected>> e : run.expectedByMemberCampaign().entrySet()) {
            String[] k = e.getKey().split(" ");
            List<JsonNode> actual = new ArrayList<>(newEarn(run, k[0], k[1]));
            List<String> actualText = actual.stream().map(HubScenariosIT::describe).toList();
            assertThat(actual).as("%s: movimenti EARN di %s per %s, attesi %s, trovati %s", run.code, k[0], k[1],
                    e.getValue(), actualText).hasSize(e.getValue().size());
            for (Expected x : e.getValue()) {
                JsonNode match = actual.stream().filter(a -> a.path("currency").asString().equals(x.currency())
                                && a.path("amount").asLong() == x.amount()
                                && (x.actionId() == null || x.actionId().equals(a.path("actionId").asString())))
                        .findFirst().orElse(null);
                assertThat(match).as("%s: movimento atteso %s tra %s", run.code, x, actualText).isNotNull();
                actual.remove(match);
            }
        }
    }

    /** Passi senza accredito (rifiutati, non abbinati, duplicati, limite raggiunto, nessuna campagna): nessun movimento. */
    private void verifyNoEffectFor(Run run) {
        for (Step s : run.steps) {
            boolean expectsEarn = run.expected.stream().anyMatch(x -> s.eventId().equals(x.actionId()));
            if (expectsEarn) {
                continue;
            }
            List<String> found = new ArrayList<>();
            for (String m : memberIds) {
                for (JsonNode e : earnEntries(m, run.ledgerFrom)) {
                    if (s.eventId().equals(e.path("actionId").asString()) && !run.earnBefore.get(m).contains(e.path("id").asString())) {
                        found.add(m + " " + describe(e));
                    }
                }
            }
            assertThat(found).as("%s passo %d (%s): nessun movimento per l'azione %s", run.code, s.index() + 1,
                    s.status(), s.eventId()).isEmpty();
            if (!"ACCEPTED".equals(s.status()) && !"DUPLICATE".equals(s.status())) {
                assertThat(status("/v1/events/{id}", s.eventId())).as("%s passo %d: nessuna azione pubblicata",
                        run.code, s.index() + 1).isEqualTo(404);
                assertThat(status("/v1/evaluations/{id}", s.eventId())).as("%s passo %d: nessuna valutazione",
                        run.code, s.index() + 1).isEqualTo(404);
            }
        }
    }

    /**
     * Un solo albero per passo (tracciati di insight): per ogni passo accettato la radice è l'azione del passo, ogni
     * altro nodo discende da un nodo dello stesso tracciato, e c'è esattamente una valutazione dell'azione (nessuna
     * per SCN-POISON, dove il tracciato è FAILED). Nessun'altra correlazione è nata dopo l'avvio della run.
     */
    private void verifyOneTreePerStep(Run run) {
        Set<String> correlations = new LinkedHashSet<>();
        for (Step s : run.steps) {
            if (!"ACCEPTED".equals(s.status())) {
                continue;
            }
            assertThat(s.correlationId()).as("%s passo %d: correlazione = id dell'azione", run.code, s.index() + 1)
                    .isEqualTo(s.eventId());
            correlations.add(s.correlationId());
            JsonNode trace = get("/v1/traces/{id}", s.correlationId());
            JsonNode nodes = trace.path("nodes");
            Set<String> ids = new HashSet<>();
            List<JsonNode> roots = new ArrayList<>();
            int evaluations = 0;
            for (JsonNode n : nodes) {
                ids.add(n.path("eventId").asString());
                if (n.path("parentEventId").isNull() || n.path("parentEventId").isMissingNode()) {
                    roots.add(n);
                }
                if ("campaign.evaluated".equals(n.path("shortType").asString())
                        && s.eventId().equals(n.path("parentEventId").asString())) {
                    evaluations++;
                }
            }
            assertThat(roots).as("%s passo %d: una sola radice nel tracciato %s", run.code, s.index() + 1, trace)
                    .hasSize(1);
            assertThat(roots.get(0).path("eventId").asString()).isEqualTo(s.eventId());
            assertThat(roots.get(0).path("family").asString()).isEqualTo("ACTION");
            for (JsonNode n : nodes) {
                String parent = n.path("parentEventId").asString(null);
                if (parent != null) {
                    assertThat(ids).as("%s passo %d: il nodo %s %s discende dallo stesso albero", run.code,
                            s.index() + 1, n.path("shortType").asString(), n.path("eventId").asString()).contains(parent);
                }
            }
            boolean poisoned = s.spec().path("data").path("_poison").asBoolean(false);
            assertThat(evaluations).as("%s passo %d: valutazioni dell'azione", run.code, s.index() + 1)
                    .isEqualTo(poisoned ? 0 : 1);
            if (poisoned) {
                assertThat(trace.path("status").asString()).as("%s passo %d: tracciato", run.code, s.index() + 1)
                        .isEqualTo("FAILED");
            } else {
                assertThat(trace.path("status").asString()).as("%s passo %d: tracciato", run.code, s.index() + 1)
                        .isIn("IN_PROGRESS", "COMPLETE");
            }
        }
        List<String> others = new ArrayList<>();
        for (JsonNode t : get("/v1/traces?from={from}&limit=100", run.start.toString()).path("items")) {
            if (!correlations.contains(t.path("correlationId").asString())) {
                others.add(t.path("correlationId").asString() + " " + t.path("rootShortType").asString()
                        + " " + t.path("memberId").asString());
            }
        }
        assertThat(others).as("%s: correlazioni nate dopo l'avvio fuori dai passi", run.code).isEmpty();
    }

    /** Nessuna voce DLQ inattesa: una sola, e solo per SCN-POISON (verificata nel dettaglio da {@link #verifyScenario}). */
    private void verifyDlq(Run run) {
        long poisoned = run.steps.stream().filter(s -> s.spec().path("data").path("_poison").asBoolean(false)).count();
        assertThat(dlqTotal()).as("%s: voci DLQ", run.code).isEqualTo(run.dlqBefore + poisoned);
    }

    // =====================================================================================================
    // Verifiche per scenario
    // =====================================================================================================

    /** Letture da fare prima dell'avvio (contatori, inbox). */
    private void prepare(Run run) {
        switch (run.code) {
            case "SCN-WEEKEND-BURST" -> run.before.put("statsMatches", stats("CMP-PURCHASE-BASE").path("matches").asLong());
            case "SCN-TIER-UP" -> run.before.put("unread", unread("MBR-000003"));
            default -> {
                // niente
            }
        }
    }

    private void verifyScenario(Run run) {
        switch (run.code) {
            case "SCN-WEEKEND-ANNA" -> {
                // Acquisto di sabato (×2) + accesso; la recensione è valutata ma CMP-REVIEW è in pausa: nessun punto.
                expectPurchaseBase(run, run.step(0));
                expectGrant(run, run.step(1), "CMP-APP-DAILY");
                JsonNode review = evaluationOf(run.step(2));
                assertThat(review.path("matched")).as("recensione: nessuna campagna scatta (CMP-REVIEW in pausa)").isEmpty();
                run.effects.add("recensione valutata senza punti");
            }
            case "SCN-MIXED-DAY" -> {
                expectGrant(run, run.step(0), "CMP-EBILL");
                expectGrant(run, run.step(1), "CMP-SURVEY");
                awaitEvent(run.step(1).correlationId(), "EFFECT", "plays.grant", null);
                run.effects.add("UNMATCHED nel monitor");
            }
            case "SCN-REJECTS" -> {
                assertThat(run.step(0).rejectCode()).as("Roberto è bloccato").isEqualTo("MEMBER_NOT_ACTIVE");
                assertThat(run.step(1).rejectCode()).as("survey.completed non ammesso da ecommerce").isEqualTo("TYPE_NOT_ALLOWED");
                run.effects.add("MEMBER_NOT_ACTIVE, TYPE_NOT_ALLOWED");
            }
            case "SCN-BAD-EVENT" -> {
                assertThat(run.step(0).rejectCode()).isEqualTo("INVALID_DATA");
                assertThat(run.step(1).rejectCode()).isEqualTo("SOURCE_DISABLED");
                assertThat(run.step(2).rejectCode()).isNull();
                assertThat(run.step(3).rejectCode()).isEqualTo("MEMBER_NOT_ACTIVE");
                run.effects.add("INVALID_DATA, SOURCE_DISABLED, UNMATCHED, MEMBER_NOT_ACTIVE");
            }
            case "SCN-ONBOARDING" -> {
                expectGrant(run, run.step(0), "CMP-APP-DAILY");
                expectGrant(run, run.step(1), "CMP-PROFILE");
                Step purchase = run.step(2);
                expectPurchaseBase(run, purchase);
                // Primo acquisto → ACH-FIRST-PURCHASE → BDG-FIRST → azione interna badge.awarded → CMP-BADGE-BONUS.
                expectBadgeChain(run, purchase, "BDG-FIRST");
            }
            case "SCN-TIER-UP" -> {
                Step purchase = run.step(0);
                expectPurchaseBase(run, purchase);
                String newTier = tierAfter(run, purchase);
                assertThat(newTier).as("docs/10 §4: Giulia sale a GOLD").isEqualTo("GOLD");
                JsonNode fact = awaitEvent(purchase.correlationId(), "FACT", "tier.upgraded", newTier);
                JsonNode bridged = awaitEvent(purchase.correlationId(), "ACTION", "tier.upgraded", null);
                assertThat(bridged.path("hop").asInt()).as("ponte interno: lhhop 1").isEqualTo(1);
                assertThat(bridged.path("source").asString()).isEqualTo(INTERNAL_SOURCE);
                assertThat(eventDetail(bridged).path("causationId").asString()).isEqualTo(fact.path("eventId").asString());
                expectGrant(run, purchase.memberId(), "CMP-TIER-UP-BONUS", data("newTier", newTier), null,
                        run.tierBefore.get(purchase.memberId()).path("code").asString());
                // Tris del mese (progresso seed 2/3 nel mese del passo) → BDG-TRIS → bonus badge.
                expectBadgeChain(run, purchase, "BDG-TRIS");
                await("SCN-TIER-UP: livello GOLD nel wallet", EFFECT_TIMEOUT_MS,
                        () -> newTier.equals(wallet(purchase.memberId()).path("tier").path("code").asString()));
                long unreadBefore = run.before.get("unread");
                await("SCN-TIER-UP: messaggi nell'inbox di Giulia", EFFECT_TIMEOUT_MS,
                        () -> unread(purchase.memberId()) > unreadBefore);
                run.effects.add("tier " + newTier + ", messaggi");
            }
            case "SCN-REFERRAL" -> {
                Step purchase = run.step(0);
                expectPurchaseBase(run, purchase);
                String referrer = "MBR-000002";
                JsonNode referee = awaitEvent(purchase.correlationId(), "FACT", "referral.completed", "REFEREE");
                JsonNode referrerFact = awaitEvent(purchase.correlationId(), "FACT", "referral.completed", "REFERRER");
                assertThat(referee.path("memberId").asString()).isEqualTo(purchase.memberId());
                assertThat(referrerFact.path("memberId").asString()).isEqualTo(referrer);
                expectGrant(run, purchase.memberId(), "CMP-REFERRAL-REFEREE", data("role", "REFEREE"), null,
                        tierOf(run, purchase.memberId()));
                expectGrant(run, referrer, "CMP-REFERRAL-REFERRER", data("role", "REFERRER"), null, tierOf(run, referrer));
                // I punti di Marco sono nello stesso albero dell'acquisto di Elisa.
                await("SCN-REFERRAL: wallet.points.earned di Marco nel tracciato di Elisa", EFFECT_TIMEOUT_MS,
                        () -> events(purchase.correlationId(), "FACT", "wallet.points.earned", null).stream()
                                .anyMatch(e -> referrer.equals(e.path("memberId").asString())));
            }
            case "SCN-DIGITAL" -> {
                expectGrant(run, run.step(0), "CMP-EBILL");
                Step debit = run.step(1);
                expectGrant(run, debit, "CMP-DIRECT-DEBIT");
                expectBadgeChain(run, debit, "BDG-DIGITAL");
            }
            case "SCN-WEEKEND-BURST" -> verifyWeekendBurst(run);
            case "SCN-DUPLICATE" -> verifyDuplicate(run);
            case "SCN-SMOKE" -> {
                Step login = run.step(0);
                expectGrant(run, login, "CMP-APP-DAILY");
                awaitEvent(login.correlationId(), "FACT", "wallet.points.earned", null);
                run.effects.add("wallet.points.earned nel tracciato");
            }
            case "SCN-POISON" -> verifyPoison(run);
            default -> throw new AssertionError("Scenario senza verifiche: " + run.code);
        }
    }

    /** Dopo le verifiche comuni (a riposo). */
    private void afterChecks(Run run) {
        if ("SCN-DIGITAL".equals(run.code)) {
            // docs/10 §8: "entra in SEG-DIGITAL al ricalcolo" (il ricalcolo in demo è manuale, member-service §5).
            await("SCN-DIGITAL: etichette ebill e directdebit a Marco", EFFECT_TIMEOUT_MS, () -> {
                Set<String> labels = new HashSet<>();
                get("/v1/members/MBR-000002").path("labels").forEach(l -> labels.add(l.asString()));
                return labels.containsAll(Set.of("ebill", "directdebit"));
            });
            client().post().uri("/v1/demo/jobs/refresh-segments").header("X-LH-Actor", ADMIN).retrieve().body(JsonNode.class);
            List<String> codes = new ArrayList<>();
            get("/v1/members/MBR-000002/segments").forEach(s -> codes.add(s.path("code").asString()));
            assertThat(codes).as("Marco dopo il ricalcolo").contains("SEG-DIGITAL").doesNotContain("SEG-NOT-EBILL");
            run.effects.add("SEG-DIGITAL");
            awaitQuiescence("SCN-DIGITAL ricalcolo");
        }
    }

    /**
     * M2: 12 azioni ACCEPTED, 12 valutazioni nell'event store (una per azione, in polling), punti ×2 del weekend col
     * moltiplicatore di livello e, sul quarto acquisto di Davide nello stesso giorno, CMP-PURCHASE-BASE scartata per
     * limite ({@code LIMIT}, Q-158) senza movimento.
     */
    private void verifyWeekendBurst(Run run) {
        assertThat(run.steps).hasSize(12);
        assertThat(run.steps).allMatch(s -> "ACCEPTED".equals(s.status()));
        Set<String> actions = new HashSet<>();
        run.steps.forEach(s -> actions.add(s.eventId()));
        assertThat(actions).as("12 azioni distinte").hasSize(12);

        long max = dailyLimit("CMP-PURCHASE-BASE");
        Map<String, Integer> perMemberDay = new HashMap<>();
        Step limited = null;
        int matches = 0;
        for (Step s : run.steps) {
            Instant at = run.businessTime.get(s.index());
            assertThat(dayOfWeek(at)).as("passo %d nel weekend", s.index() + 1).isIn("SAT", "SUN");
            String key = s.memberId() + " " + at.atZone(ROME).toLocalDate();
            int n = perMemberDay.merge(key, 1, Integer::sum);
            if (n <= max) {
                expectPurchaseBase(run, s);
                matches++;
            } else {
                limited = s;
            }
        }
        assertThat(limited).as("docs/10 §8: il limite 3/giorno scatta (quarto acquisto di Davide)").isNotNull();
        assertThat(limited.memberId()).isEqualTo("MBR-000004");
        assertThat(limited.spec().path("data").path("orderId").asString()).isEqualTo("ORD-BURST-09");

        // 12 valutazioni nell'event store (tracciati), una per azione del burst: le figlie dirette dell'azione. Le
        // azioni interne nate nello stesso albero (per esempio il badge del tris) hanno la propria valutazione.
        Map<String, Integer> evaluated = new HashMap<>();
        await("SCN-WEEKEND-BURST: 12 campaign.evaluated nell'event store", EFFECT_TIMEOUT_MS, () -> {
            evaluated.clear();
            for (Step s : run.steps) {
                for (JsonNode n : get("/v1/traces/{id}", s.correlationId()).path("nodes")) {
                    if ("FACT".equals(n.path("family").asString())
                            && "campaign.evaluated".equals(n.path("shortType").asString())
                            && s.eventId().equals(n.path("parentEventId").asString())) {
                        evaluated.merge(s.eventId(), 1, Integer::sum);
                    }
                }
            }
            return evaluated.size() == 12;
        });
        assertThat(evaluated.values()).as("una valutazione per azione: %s", evaluated).allMatch(n -> n == 1);

        // Statistiche della campagna (F-CMP-10): 11 match su 12 azioni.
        long before = run.before.get("statsMatches");
        int expectedMatches = matches;
        await("SCN-WEEKEND-BURST: CMP-PURCHASE-BASE +" + expectedMatches + " match", EFFECT_TIMEOUT_MS,
                () -> stats("CMP-PURCHASE-BASE").path("matches").asLong() - before >= expectedMatches);

        JsonNode ev = evaluationOf(limited);
        boolean skippedForLimit = false;
        for (JsonNode sk : ev.path("skipped")) {
            if ("CMP-PURCHASE-BASE".equals(sk.path("campaignCode").asString())) {
                assertThat(sk.path("reason").asString()).as("motivo di scarto del quarto acquisto (Q-158)").isEqualTo("LIMIT");
                skippedForLimit = true;
            }
        }
        assertThat(skippedForLimit).as("CMP-PURCHASE-BASE in skipped[] per il quarto acquisto: %s", ev).isTrue();
        run.checksAfterQuiet.add(() -> assertThat(stats("CMP-PURCHASE-BASE").path("matches").asLong() - before)
                .as("match di CMP-PURCHASE-BASE").isEqualTo(expectedMatches));
        run.effects.add("12 valutazioni, " + expectedMatches + " match, limite su ORD-BURST-09");
    }

    /** M2: il secondo invio è DUPLICATE; un solo accredito della campagna dopo l'avvio. */
    private void verifyDuplicate(Run run) {
        Step first = run.step(0);
        Step second = run.step(1);
        assertThat(first.status()).isEqualTo("ACCEPTED");
        assertThat(second.status()).isEqualTo("DUPLICATE");
        assertThat(second.eventId()).isEqualTo(first.eventId());
        expectPurchaseBase(run, first);
        run.checksAfterQuiet.add(() -> {
            List<JsonNode> entries = new ArrayList<>();
            for (JsonNode e : get("/v1/wallets/{m}/ledger?type=EARN&from={from}", first.memberId(), run.start.toString())) {
                if ("CMP-PURCHASE-BASE".equals(e.path("campaignCode").asString())) {
                    entries.add(e);
                }
            }
            assertThat(entries.stream().filter(e -> "PTS".equals(e.path("currency").asString())))
                    .as("un solo accredito PTS di CMP-PURCHASE-BASE dopo l'avvio").hasSize(1);
            assertThat(entries.stream().filter(e -> "STS".equals(e.path("currency").asString())))
                    .as("un solo accredito STS di CMP-PURCHASE-BASE dopo l'avvio").hasSize(1);
            assertThat(entries).allMatch(e -> first.eventId().equals(e.path("actionId").asString()));
            assertThat(events(first.correlationId(), "ACTION", null, null)).as("una sola azione pubblicata").hasSize(1);
        });
        run.effects.add("DUPLICATE, un solo accredito");
    }

    /** docs/10 §8: il consumer lh-campaign fallisce → voce DLQ (DEMO_POISON), tracciato FAILED, nessun punto. */
    private void verifyPoison(Run run) {
        Step login = run.step(0);
        JsonNode[] entry = new JsonNode[1];
        await("SCN-POISON: voce DLQ", EFFECT_TIMEOUT_MS, () -> {
            for (JsonNode d : get("/v1/dlq?size=200").path("items")) {
                if (login.correlationId().equals(d.path("correlationId").asString())) {
                    entry[0] = d;
                    return true;
                }
            }
            return false;
        });
        assertThat(entry[0].path("eventId").asString()).isEqualTo(login.eventId());
        assertThat(entry[0].path("consumer").asString()).isEqualTo("lh-campaign");
        assertThat(entry[0].path("errorCode").asString()).isEqualTo("DEMO_POISON");
        assertThat(entry[0].path("status").asString()).isEqualTo("OPEN");
        assertThat(entry[0].path("family").asString()).isEqualTo("ACTION");
        assertThat(entry[0].path("reprocessable").asBoolean()).isTrue();
        await("SCN-POISON: tracciato FAILED", EFFECT_TIMEOUT_MS,
                () -> "FAILED".equals(get("/v1/traces/{id}", login.correlationId()).path("status").asString()));
        run.effects.add("DLQ lh-campaign DEMO_POISON, tracciato FAILED");
    }

    // =====================================================================================================
    // Attese di movimenti (importi da seed/campaigns.json e seed/tiers.json)
    // =====================================================================================================

    private void expectPurchaseBase(Run run, Step s) {
        expectGrant(run, s, "CMP-PURCHASE-BASE");
    }

    /** Campagna scattata direttamente sull'azione del passo: movimenti con {@code actionId} = id del passo. */
    private void expectGrant(Run run, Step s, String campaign) {
        expectGrant(run, s.memberId(), campaign, s.spec().path("data"), s, tierOf(run, s.memberId()));
    }

    /**
     * Movimenti di {@code GRANT_POINTS} di una campagna (docs/03 §3.3–3.5): PER_AMOUNT = arrotondamento(importo /
     * unitStep) × value, FIXED = value, LOOKUP = lookup[campo]; poi i MULTIPLIER delle campagne che scattano sulla
     * stessa azione (per difetto); poi, se {@code tierMultiplierApplies} e PTS, floor(× moltiplicatore del livello).
     */
    private void expectGrant(Run run, String memberId, String campaign, JsonNode data, Step step, String tier) {
        JsonNode c = campaigns.get(campaign);
        assertThat(c).as("campagna %s nel seed", campaign).isNotNull();
        String actionType = c.path("triggerActionTypes").get(0).asString();
        Instant at = step == null ? null : run.businessTime.get(step.index());
        for (JsonNode eff : c.path("effects")) {
            if (!"GRANT_POINTS".equals(eff.path("type").asString())) {
                continue;
            }
            String currency = eff.path("currency").asString();
            BigDecimal amount = baseAmount(eff, data);
            if (step != null) {
                amount = amount.multiply(campaignMultiplier(actionType, currency, at)).setScale(0, RoundingMode.FLOOR);
            }
            if (eff.path("tierMultiplierApplies").asBoolean(false) && "PTS".equals(currency)) {
                amount = amount.multiply(tiers.get(tier).path("multiplier").decimalValue()).setScale(0, RoundingMode.FLOOR);
            }
            run.expected.add(new Expected(memberId, campaign, currency, amount.longValueExact(),
                    step == null ? null : step.eventId()));
        }
    }

    private static BigDecimal baseAmount(JsonNode eff, JsonNode data) {
        String mode = eff.path("mode").asString();
        switch (mode) {
            case "FIXED" -> {
                return eff.path("value").decimalValue();
            }
            case "PER_AMOUNT" -> {
                BigDecimal value = field(data, eff.path("amountField").asString()).decimalValue();
                BigDecimal step = eff.path("unitStep").isMissingNode() ? BigDecimal.ONE : eff.path("unitStep").decimalValue();
                RoundingMode rounding = switch (eff.path("rounding").asString("FLOOR")) {
                    case "CEIL" -> RoundingMode.CEILING;
                    case "ROUND" -> RoundingMode.HALF_UP;
                    default -> RoundingMode.FLOOR;
                };
                return value.divide(step, 0, rounding).multiply(eff.path("value").decimalValue());
            }
            case "LOOKUP" -> {
                String key = field(data, eff.path("amountField").asString()).asString();
                return eff.path("lookup").path(key).decimalValue();
            }
            default -> throw new AssertionError("Modo GRANT_POINTS non modellato dal test: " + mode);
        }
    }

    /**
     * Prodotto dei MULTIPLIER delle campagne LIVE sullo stesso tipo d'azione, per la valuta, se le loro condizioni
     * sono vere nell'istante del passo. L'unica condizione modellata è {@code context.dayOfWeek in [...]} (CMP-WEEKEND-X2):
     * una campagna moltiplicatrice con altre condizioni fa fallire il test, invece di dare un importo sbagliato.
     */
    private BigDecimal campaignMultiplier(String actionType, String currency, Instant at) {
        BigDecimal factor = BigDecimal.ONE;
        for (JsonNode c : campaigns.values()) {
            if (!"LIVE".equals(c.path("status").asString()) || !contains(c.path("triggerActionTypes"), actionType)) {
                continue;
            }
            for (JsonNode eff : c.path("effects")) {
                if (!"MULTIPLIER".equals(eff.path("type").asString()) || !currency.equals(eff.path("currency").asString())) {
                    continue;
                }
                boolean applies = true;
                for (JsonNode rule : c.path("conditions").path("rules")) {
                    if ("context.dayOfWeek".equals(rule.path("field").asString()) && "in".equals(rule.path("cmp").asString())) {
                        applies &= contains(rule.path("value"), dayOfWeek(at));
                    } else {
                        throw new AssertionError("Condizione di " + c.path("code").asString() + " non modellata dal test: " + rule);
                    }
                }
                if (applies) {
                    factor = factor.multiply(eff.path("factor").decimalValue());
                }
            }
        }
        return factor;
    }

    private long dailyLimit(String campaign) {
        for (JsonNode lim : campaigns.get(campaign).path("limits").path("perMember")) {
            if ("DAY".equals(lim.path("period").asString())) {
                return lim.path("max").asLong();
            }
        }
        throw new AssertionError(campaign + " senza limite giornaliero nel seed");
    }

    /** Livello raggiunto dopo il passo: il più alto con soglia ≤ STS dell'edizione + STS del passo (seed/tiers.json). */
    private String tierAfter(Run run, Step s) {
        JsonNode before = run.tierBefore.get(s.memberId());
        long sts = before.path("periodSts").asLong() + run.expected.stream()
                .filter(x -> s.eventId().equals(x.actionId()) && "STS".equals(x.currency())).mapToLong(Expected::amount).sum();
        String best = null;
        int rank = -1;
        for (JsonNode t : tiers.values()) {
            if (t.path("thresholdSts").asLong() <= sts && t.path("rank").asInt() > rank) {
                rank = t.path("rank").asInt();
                best = t.path("code").asString();
            }
        }
        assertThat(rank).as("il passo fa salire di livello").isGreaterThan(tiers.get(before.path("code").asString()).path("rank").asInt());
        return best;
    }

    /**
     * Catena di docs/10 §8: fatto {@code badge.awarded} col badge nello stesso albero, azione interna
     * {@code badge.awarded} (ponte, lhhop ≥ 1) e bonus di CMP-BADGE-BONUS.
     */
    private void expectBadgeChain(Run run, Step s, String badge) {
        JsonNode fact = awaitEvent(s.correlationId(), "FACT", "badge.awarded", badge);
        assertThat(fact.path("memberId").asString()).isEqualTo(s.memberId());
        await(run.code + ": azione interna badge.awarded " + badge, EFFECT_TIMEOUT_MS,
                () -> events(s.correlationId(), "ACTION", "badge.awarded", null).stream().anyMatch(a ->
                        fact.path("eventId").asString().equals(eventDetail(a).path("causationId").asString())));
        for (JsonNode a : events(s.correlationId(), "ACTION", "badge.awarded", null)) {
            assertThat(a.path("source").asString()).isEqualTo(INTERNAL_SOURCE);
            assertThat(a.path("hop").asInt()).isGreaterThanOrEqualTo(1);
        }
        expectGrant(run, s.memberId(), "CMP-BADGE-BONUS", data("badgeCode", badge), null, tierOf(run, s.memberId()));
        run.effects.add(badge);
    }

    // =====================================================================================================
    // Letture
    // =====================================================================================================

    /** Nuovi movimenti EARN del membro per la campagna (non presenti prima dell'avvio). */
    private List<JsonNode> newEarn(Run run, String memberId, String campaign) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : earnEntries(memberId, run.ledgerFrom)) {
            if (campaign.equals(e.path("campaignCode").asString()) && !run.earnBefore.get(memberId).contains(e.path("id").asString())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Movimenti EARN dalla data di business {@code from}: l'istante di business di un accredito è quello dell'azione
     * (docs/05 §2), quindi per i passi con {@code at} nel passato il filtro parte da prima dell'avvio e i movimenti
     * già esistenti si escludono con la fotografia presa prima della run.
     */
    private List<JsonNode> earnEntries(String memberId, Instant from) {
        List<JsonNode> out = new ArrayList<>();
        get("/v1/wallets/{m}/ledger?type=EARN&from={from}&limit=500", memberId, from.toString()).forEach(out::add);
        return out;
    }

    /** Il fatto {@code campaign.evaluated} dell'azione del passo (figlio diretto), col payload. */
    private JsonNode evaluationOf(Step s) {
        JsonNode[] found = new JsonNode[1];
        await("campaign.evaluated di " + s.eventId(), EFFECT_TIMEOUT_MS, () -> {
            for (JsonNode e : events(s.correlationId(), "FACT", "campaign.evaluated", null)) {
                JsonNode d = eventDetail(e);
                if (s.eventId().equals(d.path("causationId").asString())) {
                    found[0] = d.path("payload").path("data");
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** Attende un evento del tracciato (famiglia, tipo breve, testo nel payload) e ne ritorna la riga. */
    private JsonNode awaitEvent(String correlationId, String family, String type, String payloadText) {
        JsonNode[] found = new JsonNode[1];
        await(family + " " + type + (payloadText == null ? "" : " [" + payloadText + "]") + " nel tracciato " + correlationId,
                EFFECT_TIMEOUT_MS, () -> {
                    List<JsonNode> items = events(correlationId, family, type, payloadText);
                    if (items.isEmpty()) {
                        return false;
                    }
                    found[0] = items.get(0);
                    return true;
                });
        return found[0];
    }

    private List<JsonNode> events(String correlationId, String family, String type, String payloadText) {
        StringBuilder uri = new StringBuilder("/v1/events?correlationId={c}&limit=200");
        List<Object> vars = new ArrayList<>(List.of(correlationId));
        if (family != null) {
            uri.append("&family={f}");
            vars.add(family);
        }
        if (type != null) {
            uri.append("&type={t}");
            vars.add(type);
        }
        if (payloadText != null) {
            uri.append("&q={q}");
            vars.add(payloadText);
        }
        List<JsonNode> out = new ArrayList<>();
        get(uri.toString(), vars.toArray()).path("items").forEach(out::add);
        return out;
    }

    private JsonNode eventDetail(JsonNode summary) {
        return get("/v1/events/{id}", summary.path("eventId").asString());
    }

    private JsonNode wallet(String memberId) {
        return get("/v1/wallets/{m}", memberId);
    }

    private String tierOf(Run run, String memberId) {
        return run.tierBefore.get(memberId).path("code").asString();
    }

    private JsonNode stats(String campaign) {
        return get("/v1/campaigns/{c}/stats", campaign);
    }

    private long unread(String memberId) {
        return get("/v1/portal/inbox/unread-count?memberId={m}", memberId).path("unread").asLong();
    }

    private long dlqTotal() {
        return get("/v1/dlq?size=1").path("page").path("totalItems").asLong();
    }

    private JsonNode awaitRun(String runId) {
        JsonNode[] run = new JsonNode[1];
        await("fine della run " + runId, RUN_TIMEOUT_MS, () -> {
            run[0] = get("/v1/demo/scenario-runs/{id}", runId);
            return !"RUNNING".equals(run[0].path("status").asString());
        });
        return run[0];
    }

    // =====================================================================================================
    // Quiete (come HubReplayIdempotencyIT, finestra più corta)
    // =====================================================================================================

    /**
     * Nessuna scrittura su outbox, event_store, processed_event e DLQ per {@link #QUIET_WINDOW_MS}, nessuna riga
     * dell'outbox da pubblicare e il bus ha consegnato tutto ciò che aveva in coda (barriera FIFO).
     */
    private void awaitQuiescence(String phase) {
        long deadline = System.currentTimeMillis() + 60_000;
        List<Long> previous = counters();
        while (System.currentTimeMillis() < deadline) {
            sleep(QUIET_WINDOW_MS);
            barrier();
            List<Long> current = counters();
            if (current.equals(previous) && current.get(2) == 0) {
                return;
            }
            previous = current;
        }
        throw new AssertionError("Sistema non a riposo entro 60 s (" + phase + "): " + previous);
    }

    private List<Long> counters() {
        return List.of(
                count("SELECT count(*) FROM insight.event_store"),
                count("SELECT count(*) FROM outbox"),
                count("SELECT count(*) FROM outbox WHERE published_at IS NULL"),
                count("SELECT count(*) FROM processed_event"),
                count("SELECT count(*) FROM insight.dlq_entry"));
    }

    private void barrier() {
        String id = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);
        barriers.put(id, latch);
        bus.publish(new ProducerRecord<>(BARRIER_TOPIC, id, "{}"));
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).as("barriera del bus in-process").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // =====================================================================================================
    // Seed
    // =====================================================================================================

    private void loadSeed() {
        Path seed = seedDir();
        scenarios = byCode(read(seed.resolve("scenarios.json")));
        campaigns = byCode(read(seed.resolve("campaigns.json")));
        tiers = byCode(read(seed.resolve("tiers.json")));
        memberIds = new ArrayList<>();
        for (JsonNode m : read(seed.resolve("members.json"))) {
            memberIds.add(m.path("id").asString());
        }
        assertThat(memberIds).isNotEmpty().allMatch(id -> id.startsWith("MBR-"));
    }

    private static Path seedDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("seed/scenarios.json"))) {
                return dir.resolve("seed");
            }
            dir = dir.getParent();
        }
        throw new AssertionError("seed/ non trovato risalendo da " + Paths.get("").toAbsolutePath());
    }

    private JsonNode read(Path file) {
        try {
            return mapper.readTree(Files.readString(file));
        } catch (java.io.IOException e) {
            throw new AssertionError("Seed illeggibile: " + file, e);
        }
    }

    private static Map<String, JsonNode> byCode(JsonNode array) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        array.forEach(n -> out.put(n.path("code").asString(), n));
        return out;
    }

    // =====================================================================================================
    // Helper
    // =====================================================================================================

    private JsonNode data(String field, String value) {
        return mapper.createObjectNode().put(field, value);
    }

    private static JsonNode field(JsonNode data, String path) {
        String p = path.startsWith("data.") ? path.substring(5) : path;
        JsonNode n = data.path(p);
        assertThat(n.isMissingNode()).as("campo %s nei dati %s", path, data).isFalse();
        return n;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode v : array) {
            if (value.equals(v.asString())) {
                return true;
            }
        }
        return false;
    }

    private static String dayOfWeek(Instant at) {
        return at.atZone(ROME).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
    }

    private static String describe(JsonNode e) {
        return e.path("campaignCode").asString() + " " + e.path("currency").asString() + " " + e.path("amount").asLong()
                + " azione " + e.path("actionId").asString();
    }

    private void await(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(200);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Timeout in attesa di: " + what);
        }
    }

    private JsonNode get(String uri, Object... vars) {
        return client().get().uri(uri, vars).retrieve().body(JsonNode.class);
    }

    private int status(String uri, Object... vars) {
        return client().get().uri(uri, vars).exchange((req, res) -> res.getStatusCode().value());
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE).build();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =====================================================================================================
    // Modello
    // =====================================================================================================

    private record Step(int index, JsonNode spec, String memberId, String type, String expected, String eventId,
                        String correlationId, String status, String rejectCode) {
    }

    private record Expected(String memberId, String campaign, String currency, long amount, String actionId) {
        @Override
        public String toString() {
            return memberId + " " + campaign + " " + currency + " " + amount + (actionId == null ? "" : " @" + actionId);
        }
    }

    private record Result(String code, double seconds, String outcomes, String effects, String error) {
    }

    private static final class Run {
        final String code;
        final JsonNode spec;
        final List<Step> steps = new ArrayList<>();
        final Map<Integer, Instant> businessTime = new HashMap<>();
        final Map<String, Set<String>> earnBefore = new HashMap<>();
        final Map<String, JsonNode> tierBefore = new HashMap<>();
        final Map<String, Long> before = new HashMap<>();
        final List<Expected> expected = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        final List<Runnable> checksAfterQuiet = new ArrayList<>();
        Instant ledgerFrom;
        Instant start;
        String runId;
        long dlqBefore;

        Run(String code, JsonNode spec) {
            this.code = code;
            this.spec = spec;
        }

        Step step(int i) {
            return steps.get(i);
        }

        Map<String, List<Expected>> expectedByMemberCampaign() {
            Map<String, List<Expected>> out = new TreeMap<>();
            expected.forEach(x -> out.computeIfAbsent(x.memberId() + " " + x.campaign(), k -> new ArrayList<>()).add(x));
            return out;
        }

        String outcomes() {
            Map<String, Integer> n = new TreeMap<>();
            steps.forEach(s -> n.merge(s.status(), 1, Integer::sum));
            List<String> parts = new ArrayList<>();
            n.forEach((k, v) -> parts.add(v + "x" + k));
            return String.join(" ", parts);
        }

        String effectSummary() {
            Map<String, Long> totals = new LinkedHashMap<>();
            for (Expected x : expected) {
                totals.merge(x.memberId().substring(7) + " " + x.campaign().replace("CMP-", "") + " " + x.currency(),
                        x.amount(), Long::sum);
            }
            List<String> parts = new ArrayList<>();
            totals.forEach((k, v) -> parts.add(k + " +" + v));
            parts.addAll(effects);
            return String.join("; ", parts);
        }
    }
}
