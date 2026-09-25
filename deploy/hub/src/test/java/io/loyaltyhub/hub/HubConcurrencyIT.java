package io.loyaltyhub.hub;

import io.loyaltyhub.hub.bus.HubInProcessBus;
import io.loyaltyhub.ingestion.domain.ScenarioTime;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invarianti documentati sotto concorrenza reale, nel deployable consolidato (ADR-023, bus in-process ADR-024), usando
 * solo le API HTTP pubbliche: nessun membro creato, nessuna tabella scritta a mano. Ogni gara parte da un cancello
 * ({@link CountDownLatch}) e ogni scenario verifica prima che il percorso a un solo thread funzioni.
 * <ul>
 *   <li><b>Doppia spesa</b> (docs/03 §5, F-WAL-04, F-WAL-08): saldo B che basta per k = ⌊B/C⌋ richieste, N richieste
 *       concorrenti → esattamente k confermate/evase, le altre {@code REJECTED (INSUFFICIENT_BALANCE)}, saldo mai
 *       negativo e finale B − k·C, k movimenti {@code SPEND}.</li>
 *   <li><b>Gara sullo stock</b> (F-RWD-05, F-RWD-07): stock 2, 8 richieste da più membri → al più 2 accettate, stock mai
 *       sotto zero; l'annullo con rimborso (due annulli concorrenti sulla stessa richiesta) restituisce i punti in un
 *       lotto <em>nuovo</em> (docs/03 §4.2, Q-160) e ripristina lo stock di esattamente 1.</li>
 *   <li><b>Istante vincente</b> (docs/03 §6, F-IW-04, F-IW-05): un solo istante maturato, giocate concorrenti di più
 *       membri → una sola vincita e l'istante {@code CLAIMED} una volta sola.</li>
 *   <li><b>Salvataggi concorrenti</b> (M7.6, Q-112): due PUT con la stessa {@code version} su campagna, premio e
 *       concorso → un 200 e un 409 {@code VERSION_CONFLICT}.</li>
 *   <li><b>Abbinamento concorrente</b> (F-ING-04, BO-26): due {@code match} sulla stessa riga {@code UNMATCHED} → riga
 *       abbinata una volta, una sola azione pubblicata, punti accreditati una volta.</li>
 * </ul>
 * I membri sono ripartiti tra gli scenari (nessuno scenario verifica il saldo di un membro toccato da un altro), così
 * l'ordine dei test non conta. Job di sfondo che cambiano lo stato da soli spenti come in {@link HubReplayIdempotencyIT}.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=hub",
                "loyaltyhub.webhooks.dispatcher.enabled=false",
                "loyaltyhub.reward.redemption-timeout.enabled=false",
                "loyaltyhub.insight.retention.cron=-"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubConcurrencyIT {

    private static final EmbeddedPostgres PG = startPg();

    private static final String ADMIN = "ADMIN:test";
    private static final String MARKETING = "MARKETING:giulia.marketing";
    private static final String LEGAL = "LEGAL:paola.legal";
    private static final String CARE = "CARE:anna.care";
    /** Costo della fascia F1 (seed/reward-bands.json). */
    private static final long F1_COST = 500;
    private static final long TIMEOUT_MS = 30_000;
    private static final Set<String> FINAL = Set.of("CONFIRMED", "FULFILLED", "REJECTED", "CANCELLED");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private HubInProcessBus bus;

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

    // =====================================================================================================
    // 1. Doppia spesa: un membro, N richieste concorrenti, saldo per k.
    // =====================================================================================================

    @Test
    void concurrentRedemptionsNeverSpendMoreThanTheBalance() throws Exception {
        String member = "MBR-000001"; // Anna, BASE, 100 PTS nel seed: usata solo qui
        String reward = "RWD-CONC-DS-" + suffix();
        createLiveReward(reward, "INSTANT", null, null);
        awaitRedeemable(member, reward);

        // Percorso a un solo thread: saldo = C, una richiesta → FULFILLED, saldo 0.
        setBalance(member, F1_COST);
        String first = acceptedRedemption(member, reward);
        assertThat(awaitFinal(first).path("status").asString()).as("richiesta singola").isEqualTo("FULFILLED");
        awaitPts(member, 0);

        long balance = 1_700; // k = ⌊1700 / 500⌋ = 3, resto 200
        int n = 10;
        long k = balance / F1_COST;
        assertThat(k).isBetween(2L, 3L);
        setBalance(member, balance);

        AtomicLong minSeen = new AtomicLong(Long.MAX_VALUE);
        AtomicBoolean stop = new AtomicBoolean();
        Thread sampler = sampler(stop, () -> minSeen.accumulateAndGet(pts(member), Math::min));
        List<Resp> responses;
        List<JsonNode> finals = new ArrayList<>();
        try {
            responses = race(n, i -> call(HttpMethod.POST, "/v1/portal/redemptions", null,
                    Map.of("memberId", member, "rewardCode", reward)));
            for (Resp r : responses) {
                assertThat(r.status()).as("richiesta accettata (202): %s", r.body()).isEqualTo(202);
            }
            for (Resp r : responses) {
                finals.add(awaitFinal(r.body().path("redemptionId").asString()));
            }
        } finally {
            stop.set(true);
            sampler.join(5_000);
        }

        long confirmed = finals.stream().filter(f -> Set.of("CONFIRMED", "FULFILLED").contains(f.path("status").asString())).count();
        List<JsonNode> rejected = finals.stream().filter(f -> "REJECTED".equals(f.path("status").asString())).toList();
        assertThat(confirmed).as("richieste confermate o evase su %d", n).isEqualTo(k);
        assertThat(rejected).as("le altre sono respinte").hasSize((int) (n - k));
        assertThat(rejected).allSatisfy(f -> assertThat(f.path("rejectReason").asString()).isEqualTo("INSUFFICIENT_BALANCE"));

        assertThat(awaitPts(member, balance - k * F1_COST)).as("saldo finale B − k·C").isEqualTo(balance - k * F1_COST);
        assertThat(minSeen.get()).as("saldo osservato durante la gara").isGreaterThanOrEqualTo(0);

        JsonNode ledger = get("/v1/wallets/" + member + "/ledger?currency=PTS&limit=500");
        long spends = 0;
        for (JsonNode e : ledger) {
            assertThat(e.path("balanceAfter").asLong()).as("saldo dopo il movimento %s", e.path("id").asString())
                    .isGreaterThanOrEqualTo(0);
            if ("SPEND".equals(e.path("type").asString()) && reward.equals(metadata(e).path("rewardCode").asString())) {
                spends++;
            }
        }
        assertThat(spends).as("movimenti SPEND del premio (1 singola + k della gara)").isEqualTo(1 + k);

        long lotsRemaining = 0;
        for (JsonNode lot : get("/v1/wallets/" + member + "/lots")) {
            assertThat(lot.path("remaining").asLong()).isGreaterThanOrEqualTo(0);
            if ("ACTIVE".equals(lot.path("status").asString())) {
                lotsRemaining += lot.path("remaining").asLong();
            }
        }
        assertThat(lotsRemaining).as("residuo dei lotti = saldo").isEqualTo(balance - k * F1_COST);
        report("doppia spesa", "B=%d C=%d N=%d → k=%d confermate, %d INSUFFICIENT_BALANCE, saldo %d, min osservato %d",
                balance, F1_COST, n, confirmed, rejected.size(), pts(member), minSeen.get());
    }

    // =====================================================================================================
    // 2. Gara sullo stock + annullo con rimborso.
    // =====================================================================================================

    @Test
    void stockRaceNeverOversellsAndCancelRestoresExactlyOneUnit() throws Exception {
        // Membri usati solo qui, tutti con saldo abbondante nel seed (docs/10).
        List<String> members = List.of("MBR-000004", "MBR-000005", "MBR-000006", "MBR-000011");
        String reward = "RWD-CONC-ST-" + suffix();
        createLiveReward(reward, "MANUAL", 3, 10);
        for (String m : members) {
            awaitRedeemable(m, reward);
            ensureBalanceAtLeast(m, 3 * F1_COST);
        }

        // Percorso a un solo thread: una richiesta → CONFIRMED (evasione manuale), stock 3 → 2.
        String first = acceptedRedemption(members.get(0), reward);
        assertThat(awaitFinal(first).path("status").asString()).as("richiesta singola").isEqualTo("CONFIRMED");
        int stock = stockOf(reward);
        assertThat(stock).as("stock S prima della gara").isEqualTo(2);

        int requests = 8; // 4 membri × 2
        AtomicInteger minStock = new AtomicInteger(Integer.MAX_VALUE);
        AtomicBoolean stop = new AtomicBoolean();
        Thread sampler = sampler(stop, () -> minStock.accumulateAndGet(stockOf(reward), Math::min));
        List<Resp> responses;
        List<JsonNode> finals = new ArrayList<>();
        try {
            responses = race(requests, i -> call(HttpMethod.POST, "/v1/portal/redemptions", null,
                    Map.of("memberId", members.get(i % members.size()), "rewardCode", reward)));
            for (Resp r : responses) {
                if (r.status() == 202) {
                    finals.add(awaitFinal(r.body().path("redemptionId").asString()));
                } else {
                    assertThat(r.status()).as("rifiuto immediato: %s", r.body()).isEqualTo(422);
                    assertThat(r.code()).isEqualTo("REWARD_SOLD_OUT");
                }
            }
        } finally {
            stop.set(true);
            sampler.join(5_000);
        }

        assertThat(finals).as("richieste accettate: al più S = %d", stock).hasSizeLessThanOrEqualTo(stock);
        List<JsonNode> confirmed = finals.stream().filter(f -> "CONFIRMED".equals(f.path("status").asString())).toList();
        assertThat(confirmed).as("con saldo sufficiente tutte le accettate si confermano").hasSize(stock);
        assertThat(stockOf(reward)).as("stock residuo").isZero();
        assertThat(minStock.get()).as("stock osservato durante la gara").isGreaterThanOrEqualTo(0);

        // Annullo con rimborso: due annulli concorrenti (ADMIN e CARE) sulla stessa richiesta CONFIRMED.
        JsonNode victim = confirmed.get(0);
        String redemptionId = victim.path("id").asString();
        String member = victim.path("memberId").asString();
        long cost = victim.path("pointsCost").asLong();
        long before = pts(member);
        Map<String, JsonNode> lotsBefore = lotsById(member);
        long refundsBefore = countLedger(member, "REFUND");

        List<Resp> cancels = race(2, i -> call(HttpMethod.POST, "/v1/redemptions/" + redemptionId + "/cancel",
                i == 0 ? ADMIN : CARE, Map.of("reason", "Annullo di prova sotto concorrenza")));
        List<Resp> ok = cancels.stream().filter(r -> r.status() == 200).toList();
        List<Resp> refused = cancels.stream().filter(r -> r.status() != 200).toList();
        assertThat(ok).as("un solo annullo riesce: %s", cancels).hasSize(1);
        assertThat(ok.get(0).body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).status()).isEqualTo(409);
        assertThat(refused.get(0).code()).isEqualTo("REDEMPTION_NOT_CANCELLABLE");
        assertThat(stockOf(reward)).as("stock ripristinato di esattamente 1").isEqualTo(1);

        await("rimborso di " + redemptionId, () -> countLedger(member, "REFUND"), n -> n >= refundsBefore + 1);
        assertThat(awaitPts(member, before + cost)).as("saldo dopo il rimborso").isEqualTo(before + cost);
        assertThat(countLedger(member, "REFUND")).as("un solo rimborso").isEqualTo(refundsBefore + 1);

        Map<String, JsonNode> lotsAfter = lotsById(member);
        List<JsonNode> newLots = lotsAfter.entrySet().stream()
                .filter(e -> !lotsBefore.containsKey(e.getKey())).map(Map.Entry::getValue).toList();
        assertThat(newLots).as("il rimborso è un lotto nuovo (docs/03 §4.2)").hasSize(1);
        JsonNode refundLot = newLots.get(0);
        assertThat(refundLot.path("amount").asLong()).isEqualTo(cost);
        assertThat(refundLot.path("remaining").asLong()).isEqualTo(cost);
        JsonNode expiresAt = refundLot.path("expiresAt");
        if (!expiresAt.isMissingNode() && !expiresAt.isNull()) {
            assertThat(Instant.parse(expiresAt.asString())).as("scadenza ≥ oggi + 30 giorni (Q-160)")
                    .isAfter(Instant.now().plus(Duration.ofDays(29)));
        }
        lotsBefore.forEach((id, lot) -> assertThat(lotsAfter.get(id)).as("lotto d'origine %s invariato", id).isEqualTo(lot));
        report("stock", "S=%d, M=%d richieste da %d membri → %d accettate, %d REWARD_SOLD_OUT, stock min %d; "
                        + "annullo: 1×200 + 1×409, stock 0→1, rimborso %d PTS in un lotto nuovo",
                stock, requests, members.size(), finals.size(), requests - finals.size(), minStock.get(), cost);
    }

    // =====================================================================================================
    // 3. Istante vincente: un solo istante maturato, giocate concorrenti.
    // =====================================================================================================

    @Test
    void onlyOnePlayWinsTheDueInstant() throws Exception {
        // Premio fisico: nessun effetto sui saldi, quindi i giocatori possono essere anche quelli degli altri scenari.
        String contest = "IW-CONC-" + suffix();
        String prize = "CONC-BOX";
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", contest);
        body.put("name", "Concorso di prova concorrenza");
        body.put("description", "Concorso di prova per il test di concorrenza.");
        body.put("rulesText", "Regolamento di prova.");
        body.put("mechanic", "WHEEL");
        body.put("startAt", now.minusSeconds(60).toString());
        body.put("endAt", now.plus(Duration.ofDays(60)).toString());
        body.put("freePlayDaily", true);
        body.put("maxPlaysPerMemberPerDay", 5);
        body.put("distribution", "UNIFORM");
        body.put("seed", 20260925L);
        body.put("prizes", List.of(Map.of("code", prize, "name", "Borsa di prova", "type", "PHYSICAL", "quantity", 2)));
        expect(call(HttpMethod.POST, "/v1/contests", MARKETING, body), 201);
        JsonNode generated = expect(call(HttpMethod.POST, "/v1/contests/" + contest + "/instants/generate", MARKETING, Map.of()), 200);
        assertThat(generated.path("instants").asInt()).isEqualTo(2);
        transition("contests", contest, "SUBMIT", MARKETING, "IN_REVIEW");
        transition("contests", contest, "APPROVE", LEGAL, "APPROVED");
        transition("contests", contest, "PUBLISH", MARKETING, "LIVE");
        assertThat(dueOpenInstants(contest)).as("nessun istante maturato prima del piantato").isEmpty();

        // Percorso a un solo thread: istante piantato → una giocata vince.
        String soloPlayer = "MBR-000010";
        expect(call(HttpMethod.POST, "/v1/demo/contests/" + contest + "/plant-instant", ADMIN, Map.of("prizeCode", prize)), 200);
        JsonNode solo = expect(call(HttpMethod.POST, "/v1/portal/contests/" + contest + "/play", null, Map.of("memberId", soloPlayer)), 200);
        assertThat(solo.path("outcome").asString()).as("giocata singola sull'istante piantato").isEqualTo("WIN");

        // Un solo istante maturato: quello piantato ora.
        JsonNode planted = expect(call(HttpMethod.POST, "/v1/demo/contests/" + contest + "/plant-instant", ADMIN, Map.of("prizeCode", prize)), 200);
        String instantId = planted.path("instantId").asString();
        List<JsonNode> due = dueOpenInstants(contest);
        assertThat(due).as("istanti OPEN maturati prima della gara").hasSize(1);
        assertThat(due.get(0).path("id").asString()).isEqualTo(instantId);

        List<String> players = List.of("MBR-000001", "MBR-000002", "MBR-000003", "MBR-000004", "MBR-000005",
                "MBR-000006", "MBR-000007", "MBR-000009", "MBR-000011");
        for (String m : players) {
            assertThat(playsAvailable(contest, m)).as("giocate disponibili per %s", m).isGreaterThanOrEqualTo(1);
        }
        List<Resp> plays = race(players.size(), i -> call(HttpMethod.POST, "/v1/portal/contests/" + contest + "/play", null,
                Map.of("memberId", players.get(i))));
        List<String> winners = new ArrayList<>();
        String winningPlay = null;
        for (int i = 0; i < plays.size(); i++) {
            Resp p = plays.get(i);
            assertThat(p.status()).as("giocata di %s: %s", players.get(i), p.body()).isEqualTo(200);
            if ("WIN".equals(p.body().path("outcome").asString())) {
                winners.add(players.get(i));
                winningPlay = p.body().path("playId").asString();
                assertThat(p.body().path("prize").path("code").asString()).isEqualTo(prize);
            }
        }
        assertThat(winners).as("vincitori dell'istante").hasSize(1);

        JsonNode instants = get("/v1/contests/" + contest + "/instants?size=100");
        JsonNode claimed = null;
        int claimedCount = 0;
        int openCount = 0;
        for (JsonNode w : instants.path("items")) {
            switch (w.path("status").asString()) {
                case "CLAIMED" -> claimedCount++;
                case "OPEN" -> openCount++;
                default -> { }
            }
            if (instantId.equals(w.path("id").asString())) {
                claimed = w;
            }
        }
        assertThat(claimed).as("istante piantato").isNotNull();
        assertThat(claimed.path("status").asString()).isEqualTo("CLAIMED");
        assertThat(claimed.path("claimedBy").asString()).isEqualTo(winners.get(0));
        assertThat(claimed.path("playId").asString()).isEqualTo(winningPlay);
        assertThat(claimedCount).as("istanti CLAIMED (singola + gara)").isEqualTo(2);
        assertThat(openCount).isZero();

        JsonNode view = get("/v1/contests/" + contest);
        assertThat(view.path("prizesRemaining").asInt()).as("montepremi residuo").isZero();
        assertThat(view.path("wins").asLong()).as("vincite registrate").isEqualTo(2);
        for (JsonNode p : view.path("prizes")) {
            assertThat(p.path("quantityRemaining").asInt()).isGreaterThanOrEqualTo(0);
        }
        report("istante vincente", "1 istante maturato, %d giocate concorrenti → 1 WIN (%s), istante CLAIMED una volta",
                players.size(), winners.get(0));
    }

    // =====================================================================================================
    // 4. Salvataggi concorrenti degli editor (optimistic locking).
    // =====================================================================================================

    @Test
    void concurrentEditorSavesWithTheSameVersionConflict() throws Exception {
        String reward = "RWD-CONC-ED-" + suffix();
        Map<String, Object> r = new LinkedHashMap<>(rewardBody(reward, "MANUAL", 10, null));
        expect(call(HttpMethod.POST, "/v1/rewards", MARKETING, r), 201);

        String contest = "IW-CONC-ED-" + suffix();
        Instant now = Instant.now();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", contest);
        c.put("name", "Concorso editor");
        c.put("mechanic", "BOX");
        c.put("startAt", now.plus(Duration.ofDays(10)).toString());
        c.put("endAt", now.plus(Duration.ofDays(20)).toString());
        c.put("freePlayDaily", true);
        c.put("distribution", "UNIFORM");
        c.put("prizes", List.of(Map.of("code", "P1", "name", "Premio", "type", "PHYSICAL", "quantity", 1)));
        expect(call(HttpMethod.POST, "/v1/contests", MARKETING, c), 201);

        int rounds = 3;
        for (String[] target : List.of(
                new String[]{"/v1/campaigns/CMP-REVIEW", "campagna"},
                new String[]{"/v1/rewards/" + reward, "premio"},
                new String[]{"/v1/contests/" + contest, "concorso"})) {
            for (int round = 0; round < rounds; round++) {
                String uri = target[0];
                long version = get(uri).path("version").asLong();
                String[] names = {"Salvataggio A " + round, "Salvataggio B " + round};
                List<Resp> saves = race(2, i -> call(HttpMethod.PUT, uri, MARKETING,
                        Map.of("name", names[i], "version", version)));
                List<Integer> statuses = saves.stream().map(Resp::status).sorted().toList();
                assertThat(statuses).as("%s, giro %d: %s", target[1], round, saves).containsExactly(200, 409);
                Resp loser = saves.stream().filter(s -> s.status() == 409).findFirst().orElseThrow();
                assertThat(loser.code()).isEqualTo("VERSION_CONFLICT");
                int winner = saves.get(0).status() == 200 ? 0 : 1;
                JsonNode after = get(uri);
                assertThat(after.path("version").asLong()).as("una sola scrittura").isEqualTo(version + 1);
                assertThat(after.path("name").asString()).isEqualTo(names[winner]);
            }
        }
        report("salvataggi", "campagna, premio, concorso × %d giri: ogni giro 1×200 + 1×409 VERSION_CONFLICT", rounds);
    }

    // =====================================================================================================
    // 5. Abbinamento concorrente di un evento non abbinato.
    // =====================================================================================================

    @Test
    void concurrentMatchesPublishOneActionAndCreditOnce() throws Exception {
        String member = "MBR-000009"; // Elisa, BASE (×1): usata solo qui
        String topic = "lh.actions.v1";
        Map<String, AtomicInteger> published = new java.util.concurrent.ConcurrentHashMap<>();
        // Sonda di sola lettura sul bus: conta le pubblicazioni per id evento (event_store di insight deduplica).
        bus.subscribe(topic, "lh-test-concurrency-probe", rec -> published
                .computeIfAbsent(mapper.readTree(rec.value()).path("id").asString(), k -> new AtomicInteger())
                .incrementAndGet());
        String weekday = ScenarioTime.resolve("@lastWeekdayT10:00", Instant.now()).toString();

        // Percorso a un solo thread: evento non abbinato → abbina → accredito.
        String soloEvent = unmatchedPurchase(weekday);
        String soloRow = inboundRowId(soloEvent);
        JsonNode solo = expect(call(HttpMethod.POST, "/v1/inbound-events/" + soloRow + "/match", CARE, Map.of("memberId", member)), 200);
        assertThat(solo.path("status").asString()).isEqualTo("ACCEPTED");
        await("accredito dell'abbinamento singolo", () -> credits(member, soloEvent), n -> n == 1);

        String event = unmatchedPurchase(weekday);
        String row = inboundRowId(event);
        List<Resp> matches = race(2, i -> call(HttpMethod.POST, "/v1/inbound-events/" + row + "/match",
                i == 0 ? CARE : ADMIN, Map.of("memberId", member)));
        List<Resp> ok = matches.stream().filter(m -> m.status() == 200).toList();
        List<Resp> refused = matches.stream().filter(m -> m.status() != 200).toList();
        assertThat(ok).as("un solo abbinamento riesce: %s", matches).hasSize(1);
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).status()).isEqualTo(409);
        assertThat(refused.get(0).code()).isIn("INBOUND_NOT_UNMATCHED", "INBOUND_NOT_RETRYABLE");

        JsonNode detail = get("/v1/inbound-events/" + row);
        assertThat(detail.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(detail.path("memberId").asString()).isEqualTo(member);
        assertThat(detail.path("resolution").asString()).isEqualTo("MANUAL_MATCH");

        // Barriera: un'azione successiva dello stesso membro. Outbox in ordine di scrittura e bus FIFO: quando il suo
        // accredito è nel libro mastro, ogni eventuale seconda pubblicazione dell'abbinamento è già stata consegnata.
        String sentinel = acceptedPurchase(member, weekday);
        await("accredito della barriera", () -> credits(member, sentinel), n -> n == 1);

        assertThat(published.getOrDefault(event, new AtomicInteger()).get()).as("azioni pubblicate per %s", event).isEqualTo(1);
        assertThat(credits(member, event)).as("accrediti PTS dell'azione abbinata").isEqualTo(1);
        long amount = 0;
        for (JsonNode e : get("/v1/wallets/" + member + "/ledger?currency=PTS&limit=500")) {
            if (event.equals(e.path("actionId").asString())) {
                amount += e.path("amount").asLong();
            }
        }
        assertThat(amount).as("100 € da BASE → 100 PTS").isEqualTo(100);
        report("abbinamento", "2 match concorrenti → 1×200 + 1×409 %s, 1 azione pubblicata, 1 accredito da %d PTS",
                refused.get(0).code(), amount);
    }

    // ---------- premi ----------

    private Map<String, Object> rewardBody(String code, String fulfilment, Integer stockTotal, Integer perMemberLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Premio di prova " + code);
        body.put("description", "Premio creato dal test di concorrenza.");
        body.put("terms", "Nessun termine.");
        body.put("type", "DIGITAL");
        body.put("category", "TEMPO");
        body.put("band", "F1");
        body.put("fulfilment", fulfilment);
        if (stockTotal != null) {
            body.put("stockTotal", stockTotal);
        }
        if (perMemberLimit != null) {
            body.put("perMemberLimit", perMemberLimit);
        }
        return body;
    }

    /** Premio creato da MARKETING e portato LIVE col ciclo di vita comune: invio → approvazione LEGAL → pubblicazione. */
    private void createLiveReward(String code, String fulfilment, Integer stockTotal, Integer perMemberLimit) {
        JsonNode created = expect(call(HttpMethod.POST, "/v1/rewards", MARKETING,
                rewardBody(code, fulfilment, stockTotal, perMemberLimit)), 201);
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");
        transition("rewards", code, "SUBMIT", MARKETING, "IN_REVIEW");
        transition("rewards", code, "APPROVE", LEGAL, "APPROVED");
        transition("rewards", code, "PUBLISH", MARKETING, "LIVE");
    }

    private void transition(String collection, String code, String action, String actor, String expected) {
        JsonNode after = expect(call(HttpMethod.POST, "/v1/" + collection + "/" + code + "/transitions", actor,
                Map.of("action", action, "comment", "Test di concorrenza")), 200);
        assertThat(after.path("status").asString()).as("%s %s", action, code).isEqualTo(expected);
    }

    /** Il premio è nel catalogo del portale per il membro e richiedibile (niente blocco di livello, stock, limite). */
    private void awaitRedeemable(String memberId, String rewardCode) {
        await(rewardCode + " richiedibile da " + memberId, () -> catalogItem(memberId, rewardCode), item -> item != null
                && absent(item.path("lockedByTier"))
                && !"SOLD_OUT".equals(item.path("stockState").asString())
                && !item.path("perMemberLimitReached").asBoolean(false));
    }

    private JsonNode catalogItem(String memberId, String rewardCode) {
        for (JsonNode band : get("/v1/portal/catalog?memberId=" + memberId).path("bands")) {
            for (JsonNode item : band.path("rewards")) {
                if (rewardCode.equals(item.path("code").asString())) {
                    return item;
                }
            }
        }
        return null;
    }

    private String acceptedRedemption(String memberId, String rewardCode) {
        JsonNode r = expect(call(HttpMethod.POST, "/v1/portal/redemptions", null,
                Map.of("memberId", memberId, "rewardCode", rewardCode)), 202);
        return r.path("redemptionId").asString();
    }

    /** Stato della richiesta fino a uno stato diverso da {@code PENDING}. */
    private JsonNode awaitFinal(String redemptionId) {
        return await("esito della richiesta " + redemptionId, () -> get("/v1/portal/redemptions/" + redemptionId),
                r -> FINAL.contains(r.path("status").asString()));
    }

    private int stockOf(String rewardCode) {
        return get("/v1/rewards/" + rewardCode).path("stockRemaining").asInt();
    }

    // ---------- wallet ----------

    private long pts(String memberId) {
        return get("/v1/portal/wallets/" + memberId).path("balances").path("PTS").path("active").asLong();
    }

    private long awaitPts(String memberId, long expected) {
        return await("saldo " + expected + " PTS di " + memberId, () -> pts(memberId), v -> v == expected);
    }

    /** Porta il saldo al valore indicato con una rettifica ADMIN e attende che il portale lo mostri. */
    private void setBalance(String memberId, long target) {
        long current = pts(memberId);
        if (current != target) {
            adjust(memberId, target > current ? "CREDIT" : "DEBIT", Math.abs(target - current));
        }
        awaitPts(memberId, target);
    }

    private void ensureBalanceAtLeast(String memberId, long minimum) {
        long current = pts(memberId);
        if (current < minimum) {
            adjust(memberId, "CREDIT", minimum - current);
            awaitPts(memberId, minimum);
        }
    }

    private void adjust(String memberId, String direction, long amount) {
        expect(call(HttpMethod.POST, "/v1/wallets/" + memberId + "/adjustments", ADMIN, Map.of(
                "currency", "PTS", "direction", direction, "amount", amount, "reason", "TEST",
                "note", "Preparazione del test di concorrenza")), 200);
    }

    private long countLedger(String memberId, String type) {
        return get("/v1/wallets/" + memberId + "/ledger?currency=PTS&type=" + type + "&limit=500").size();
    }

    /** Movimenti PTS del membro generati dall'azione indicata ({@code actionId}). */
    private long credits(String memberId, String actionId) {
        long n = 0;
        for (JsonNode e : get("/v1/wallets/" + memberId + "/ledger?currency=PTS&limit=500")) {
            if (actionId.equals(e.path("actionId").asString())) {
                n++;
            }
        }
        return n;
    }

    private Map<String, JsonNode> lotsById(String memberId) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode lot : get("/v1/wallets/" + memberId + "/lots")) {
            out.put(lot.path("id").asString(), lot);
        }
        return out;
    }

    private JsonNode metadata(JsonNode ledgerEntry) {
        String json = ledgerEntry.path("metadataJson").asString(null);
        return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
    }

    // ---------- concorsi ----------

    private List<JsonNode> dueOpenInstants(String contest) {
        List<JsonNode> due = new ArrayList<>();
        Instant now = Instant.now();
        for (JsonNode w : get("/v1/contests/" + contest + "/instants?status=OPEN&size=100").path("items")) {
            if (!Instant.parse(w.path("instantAt").asString()).isAfter(now)) {
                due.add(w);
            }
        }
        return due;
    }

    private int playsAvailable(String contest, String memberId) {
        for (JsonNode c : get("/v1/portal/contests?memberId=" + memberId)) {
            if (contest.equals(c.path("code").asString())) {
                return c.path("playsAvailable").asInt();
            }
        }
        return 0;
    }

    // ---------- ingresso ----------

    private String unmatchedPurchase(String time) {
        String id = "conc-inb-" + UUID.randomUUID();
        JsonNode r = expect(call(HttpMethod.POST, "/v1/events", null, purchase(id, "email:socio-" + id + "@example.invalid", time)), 202);
        assertThat(r.path("status").asString()).as("evento senza membro").isEqualTo("UNMATCHED");
        return id;
    }

    private String acceptedPurchase(String memberId, String time) {
        String id = "conc-inb-" + UUID.randomUUID();
        JsonNode r = expect(call(HttpMethod.POST, "/v1/events", null, purchase(id, "member:" + memberId, time)), 202);
        assertThat(r.path("status").asString()).isEqualTo("ACCEPTED");
        return id;
    }

    private static Map<String, Object> purchase(String id, String subject, String time) {
        return Map.of("specversion", "1.0", "id", id, "source", "urn:loyaltyhub:source:ecommerce",
                "type", "purchase.completed", "subject", subject, "time", time,
                "data", Map.of("orderId", "ORD-" + id, "amount", 100, "currency", "EUR", "channel", "ONLINE"));
    }

    private String inboundRowId(String eventId) {
        JsonNode rows = await("riga UNMATCHED di " + eventId,
                () -> get("/v1/inbound-events?status=UNMATCHED&q=" + eventId), r -> r.size() == 1);
        return rows.get(0).path("id").asString();
    }

    // ---------- concorrenza ----------

    /** N compiti che partono insieme dal cancello; risultati nell'ordine dei compiti. */
    private <T> List<T> race(int n, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("partecipanti pronti").isTrue();
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Campionatore in sottofondo finché {@code stop}: legge un valore che non deve mai violare l'invariante. */
    private Thread sampler(AtomicBoolean stop, Runnable sample) {
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                try {
                    sample.run();
                } catch (RuntimeException ignored) {
                    // lettura transitoria fallita: il prossimo campione conta
                }
            }
        }, "lh-test-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- HTTP ----------

    private record Resp(int status, JsonNode body) {
        String code() {
            return body == null ? null : body.path("code").asString(null);
        }
    }

    private Resp call(HttpMethod method, String uri, String actor, Object body) {
        RestClient.RequestBodySpec spec = client().method(method).uri(uri);
        if (actor != null) {
            spec = spec.header("X-LH-Actor", actor);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Resp(res.getStatusCode().value(), bytes.length == 0 ? null : mapper.readTree(bytes));
        });
    }

    private JsonNode expect(Resp r, int status) {
        assertThat(r.status()).as("HTTP %d atteso, corpo: %s", status, r.body()).isEqualTo(status);
        return r.body();
    }

    private JsonNode get(String uri) {
        return expect(call(HttpMethod.GET, uri, ADMIN, null), 200);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static <T> T await(String what, Supplier<T> probe, Predicate<T> done) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = probe.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Timeout in attesa di: " + what + " (ultimo valore: " + last + ")");
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static void report(String scenario, String format, Object... args) {
        System.out.println("[HubConcurrencyIT] " + scenario + ": " + String.format(format, args));
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
