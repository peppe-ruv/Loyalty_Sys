package io.loyaltyhub.wallet.application;

import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.stateful.Action;
import net.jqwik.api.stateful.ActionSequence;
import net.jqwik.api.statistics.Statistics;
import org.flywaydb.core.Flyway;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proprietà generative del libro mastro dei punti (docs/03 §4.1–4.3, docs/servizi/wallet-service.md §5; F-WAL-02,
 * F-WAL-04…06, F-TIER-02; ADR-053 decisione 1, Q-690).
 *
 * <p>Per qualunque sequenza di accrediti (anche in attesa e con moltiplicatore di livello), rettifiche, spese e
 * rimborsi di richieste premio, scadenze e rilasci, con l'orologio che avanza fino a valicare i dodici mesi, sul
 * codice <em>reale</em> ({@link WalletService}, {@link RedemptionPayments} e i repository) con Postgres embedded e le
 * migrazioni Flyway di produzione, valgono dopo ogni passo:
 * <ul>
 *   <li>saldo attivo = Σ residui dei lotti {@code ACTIVE}, in attesa = Σ dei {@code PENDING}, mai negativo;</li>
 *   <li>nessun lotto negativo o oltre l'importo; stato coerente col residuo;</li>
 *   <li>accreditato + rimborsato = speso + scaduto + (attivo + in attesa) (conservazione dei punti);</li>
 *   <li>un lotto non scaduto ha speso esattamente quanto dice {@code lot_consumption};</li>
 *   <li>la spesa consuma prima i lotti che scadono prima, poi i più vecchi (FIFO), tutto o niente;</li>
 *   <li>la scadenza a {@code expiresAt ≤ asOf} (estremo incluso) e il rilascio a {@code availableAt ≤ asOf};</li>
 *   <li>punti status e livello: il livello non scende mai, è il più alto con soglia ≤ punti status dell'edizione.</li>
 * </ul>
 * Le sole parti non esercitate dal codice di produzione sono i listener Kafka e la transazione Spring (qui ogni
 * istruzione è in autocommit): la regola di dominio sì, per intero. Gli importi attesi vengono dal test (contatori
 * propri e istantanee dello stato prima del passo), non da una seconda implementazione della regola.
 */
class WalletLedgerProperties {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final String PTS = "PTS";
    private static final String STS = "STS";
    private static final String MEMBER = "MBR-PROP-1";

    private static EmbeddedPostgres pg;
    private static SingleConnectionDataSource dataSource;
    private static JdbcClient jdbc;
    private static WalletRepository wallets;
    private static LedgerRepository ledger;
    private static TierRepository tiers;
    private static MemberTierRepository memberTiers;
    private static CurrencyRepository currencies;
    private static PointsLotRepository lots;
    private static TierHistoryRepository tierHistory;
    private static EditionRepository editions;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OutboxWriter OUTBOX = Mockito.mock(OutboxWriter.class);
    private static final AuditPublisher AUDIT = Mockito.mock(AuditPublisher.class);

    @BeforeContainer
    static void startDatabase() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        Flyway.configure()
                .dataSource(pg.getPostgresDatabase())
                .locations("classpath:db/migration/common", "classpath:db/migration/wallet")
                .schemas("wallet").defaultSchema("wallet").createSchemas(true)
                .load().migrate();
        dataSource = new SingleConnectionDataSource(
                pg.getJdbcUrl("postgres", "postgres") + "&currentSchema=wallet", "postgres", "", true);
        jdbc = JdbcClient.create(dataSource);
        wallets = new WalletRepository(jdbc);
        ledger = new LedgerRepository(jdbc);
        tiers = new TierRepository(jdbc, MAPPER);
        memberTiers = new MemberTierRepository(jdbc);
        currencies = new CurrencyRepository(jdbc);
        lots = new PointsLotRepository(jdbc);
        tierHistory = new TierHistoryRepository(jdbc);
        editions = new EditionRepository(jdbc);
    }

    @AfterContainer
    static void stopDatabase() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (pg != null) {
            pg.close();
        }
    }

    /** Ogni tentativo (e ogni ripetizione dello shrinking) parte da un database senza movimenti. */
    @BeforeTry
    void cleanSlate() {
        // DELETE e non TRUNCATE: su tabelle piccole è un ordine di grandezza più veloce, e qui si ripete a ogni tentativo.
        jdbc.sql("DELETE FROM ledger_entry").update();
        jdbc.sql("DELETE FROM points_lot").update();
        jdbc.sql("DELETE FROM lot_consumption").update();
        jdbc.sql("DELETE FROM wallet").update();
        jdbc.sql("DELETE FROM member_tier").update();
        jdbc.sql("DELETE FROM tier_history").update();
    }

    // ------------------------------------------------------------------------------------------------------------
    // La proprietà
    // ------------------------------------------------------------------------------------------------------------

    @Property(tries = 1000)
    void ledgerStaysConsistentUnderAnyOperationSequence(@ForAll("operations") ActionSequence<World> sequence) {
        World world = new World();
        ActionSequence<World> checked = sequence.withInvariant("libro mastro coerente", World::checkInvariants);
        checked.run(world);

        // Anti-vacuità: la sequenza generata deve davvero raggiungere i rami interessanti (percentuale delle prove).
        for (String feature : FEATURES) {
            Statistics.label(feature).collect(world.flags.contains(feature));
        }
        FEATURES.forEach(feature -> Statistics.label(feature)
                .coverage(c -> c.check(true).percentage(p -> p >= 1.0)));
    }

    /** Rami che almeno l'1% delle sequenze deve toccare: se un generatore regredisce, la proprietà diventa vuota e fallisce. */
    private static final List<String> FEATURES = List.of("spesa su più lotti", "spesa rifiutata", "addebito rifiutato",
            "lotto scaduto", "scadenza all'istante esatto", "lotto rilasciato", "accredito in attesa", "rimborso",
            "livello salito", "moltiplicatore di livello");

    @Provide
    Arbitrary<ActionSequence<World>> operations() {
        Arbitrary<Action<World>> credits = Arbitraries.frequencyOf(
                Tuple.of(4, grants()),
                Tuple.of(3, bursts()),
                Tuple.of(1, promotions()),
                Tuple.of(1, adjustCredits()));
        Arbitrary<Action<World>> all = Arbitraries.frequencyOf(
                Tuple.of(7, credits),
                Tuple.of(4, spends()),
                Tuple.of(1, adjustDebits()),
                Tuple.of(1, refunds()),
                Tuple.of(2, advances()),
                Tuple.of(2, expiries()),
                Tuple.of(2, releases()));
        return Arbitraries.integers().between(1, 8).flatMap(size -> Arbitraries.sequences(all).ofSize(size));
    }

    private static Arbitrary<Action<World>> grants() {
        Arbitrary<Long> ptsAmount = Arbitraries.longs().between(1, 600);
        Arbitrary<Long> stsAmount = Arbitraries.longs().between(200, 4_000);
        Arbitrary<Integer> pendingDays = Arbitraries.frequencyOf(
                Tuple.of(2, Arbitraries.just(0)), Tuple.of(1, Arbitraries.integers().between(1, 30)));
        Arbitrary<Action<World>> pts = net.jqwik.api.Combinators
                .combine(ptsAmount, pendingDays, Arbitraries.of(true, false), Arbitraries.of(true, false))
                .as((a, d, tierMultiplier, replay) -> new Grant(PTS, a, d, tierMultiplier, replay));
        Arbitrary<Action<World>> sts = net.jqwik.api.Combinators
                .combine(stsAmount, pendingDays, Arbitraries.of(true, false))
                .as((a, d, replay) -> new Grant(STS, a, d, false, replay));
        return Arbitraries.frequencyOf(Tuple.of(5, pts), Tuple.of(2, sts));
    }

    /** Più accrediti ravvicinati di PTS, a scadenze diverse: i lotti tra cui deve scegliere la spesa FIFO. */
    private static Arbitrary<Action<World>> bursts() {
        return net.jqwik.api.Combinators.combine(
                Arbitraries.longs().between(1, 600).list().ofMinSize(2).ofMaxSize(3),
                Arbitraries.integers().between(0, 70)).as(Burst::new);
    }

    /** Punti status che fanno salire il livello, poi un accredito PTS col moltiplicatore del nuovo livello. */
    private static Arbitrary<Action<World>> promotions() {
        return net.jqwik.api.Combinators.combine(Arbitraries.longs().between(1_000, 8_000),
                Arbitraries.longs().between(1, 600)).as(Promotion::new);
    }

    private static Arbitrary<Action<World>> adjustCredits() {
        return Arbitraries.longs().between(1, 1_000).map(AdjustCredit::new);
    }

    /** Importo in rapporto al saldo attivo, con i valori a ridosso del saldo (−1, esatto, +1). */
    private static Arbitrary<Fraction> fractions() {
        Arbitrary<Integer> pct = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.of(10, 25, 50, 75, 100)),
                Tuple.of(2, Arbitraries.integers().between(1, 130)));
        return net.jqwik.api.Combinators.combine(pct, Arbitraries.of(-1, 0, 0, 0, 1)).as(Fraction::new);
    }

    private static Arbitrary<Action<World>> spends() {
        return net.jqwik.api.Combinators.combine(fractions(), Arbitraries.of(true, false))
                .as((f, replay) -> new Spend(f, replay));
    }

    private static Arbitrary<Action<World>> adjustDebits() {
        return fractions().map(AdjustDebit::new);
    }

    private static Arbitrary<Action<World>> refunds() {
        return Arbitraries.integers().between(0, 20).map(Refund::new);
    }

    private static Arbitrary<Action<World>> advances() {
        // Giorni: pochi, qualche mese, oltre i dodici mesi della scadenza a rolling.
        return Arbitraries.frequencyOf(
                        Tuple.of(4, Arbitraries.integers().between(0, 5)),
                        Tuple.of(3, Arbitraries.integers().between(10, 120)),
                        Tuple.of(2, Arbitraries.integers().between(200, 430)))
                .map(Advance::new);
    }

    private static Arbitrary<Action<World>> expiries() {
        return Arbitraries.of(Boundary.values()).map(ExpireJob::new);
    }

    private static Arbitrary<Action<World>> releases() {
        return Arbitraries.of(Boundary.values()).map(ReleaseJob::new);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Azioni
    // ------------------------------------------------------------------------------------------------------------

    /** Istante di riferimento di un job: adesso, esattamente alla prossima scadenza, un microsecondo prima. */
    enum Boundary { NOW, AT_NEXT, JUST_BEFORE_NEXT }

    record Fraction(int pct, int delta) {
        long of(long balance) {
            return Math.max(1, balance * pct / 100 + delta);
        }
    }

    record Advance(int days) implements Action<World> {
        @Override
        public World run(World w) {
            w.clock.set(w.clock.instant().plus(days, ChronoUnit.DAYS).plus(37, ChronoUnit.MINUTES));
            return w;
        }
    }

    record Burst(List<Long> amounts, int gapDays) implements Action<World> {
        @Override
        public World run(World w) {
            for (long amount : amounts) {
                new Grant(PTS, amount, 0, false, false).run(w);
                w.clock.set(w.clock.instant().plus(gapDays, ChronoUnit.DAYS));
            }
            return w;
        }
    }

    record Promotion(long sts, long pts) implements Action<World> {
        @Override
        public World run(World w) {
            new Grant(STS, sts, 0, false, false).run(w);
            new Grant(PTS, pts, 0, true, false).run(w);
            return w;
        }
    }

    record Grant(String currency, long amount, int pendingDays, boolean tierMultiplier, boolean replay)
            implements Action<World> {
        @Override
        public World run(World w) {
            String effectId = "EFF-" + (++w.counter);
            LhEvent<JsonNode> event = w.grantEvent(effectId, currency, amount, pendingDays, tierMultiplier);
            Instant now = w.clock.instant();
            BigDecimal multiplier = w.tierMultiplier();

            w.walletService.applyGrant(event);

            long expected = PTS.equals(currency) && tierMultiplier
                    ? BigDecimal.valueOf(amount).multiply(multiplier).setScale(0, RoundingMode.FLOOR).longValueExact()
                    : amount;
            String entryId = jdbc.sql("SELECT id FROM ledger_entry WHERE effect_id = ?").param(effectId)
                    .query(String.class).single();
            Lot lot = lots(MEMBER).stream().filter(l -> entryId.equals(l.ledgerEntryId())).findFirst().orElseThrow();
            assertThat(lot.amount()).as("importo accreditato").isEqualTo(expected);
            assertThat(lot.earnedAt()).isEqualTo(now);
            if (pendingDays > 0) {
                assertThat(lot.status()).isEqualTo("PENDING");
                assertThat(lot.availableAt()).isEqualTo(now.plus(pendingDays, ChronoUnit.DAYS));
                w.flags.add("accredito in attesa");
            } else {
                assertThat(lot.status()).isEqualTo("ACTIVE");
            }
            if (PTS.equals(currency)) {
                w.credited += expected;
                // Scadenza a rolling di 12 mesi: ultimo istante del mese di earnedAt + 12 mesi, in Europe/Rome.
                Instant firstInstantOfNextMonth = YearMonth.from(now.atZone(ROME)).plusMonths(13)
                        .atDay(1).atStartOfDay(ROME).toInstant();
                assertThat(lot.expiresAt()).isEqualTo(firstInstantOfNextMonth.minus(1, ChronoUnit.MICROS));
                if (tierMultiplier && multiplier.compareTo(BigDecimal.ONE) > 0) {
                    w.flags.add("moltiplicatore di livello");
                }
            } else {
                assertThat(lot.expiresAt()).as("gli STS non scadono a lotto").isNull();
                if (pendingDays == 0) {
                    w.stsCounted += expected;
                }
            }
            if (replay) { // RNF-03: stesso effectId due volte = un solo movimento
                Snapshot before = w.snapshot();
                w.walletService.applyGrant(event);
                assertThat(w.snapshot()).as("doppio invio = stesso stato").isEqualTo(before);
            }
            return w;
        }
    }

    record AdjustCredit(long amount) implements Action<World> {
        @Override
        public World run(World w) {
            long before = wallet(PTS).active();
            var result = w.walletService.adjustBalance(MEMBER, PTS, "CREDIT", amount, "GOODWILL", "rettifica di prova");
            assertThat(result.balanceAfter()).isEqualTo(before + amount);
            w.credited += amount;
            return w;
        }
    }

    record AdjustDebit(Fraction fraction) implements Action<World> {
        @Override
        public World run(World w) {
            long before = wallet(PTS).active();
            long amount = fraction.of(before);
            List<Lot> pre = activeLots(PTS);
            Snapshot snapshot = w.snapshot();
            if (amount > before) {
                assertThatThrownBy(() -> w.walletService.adjustBalance(MEMBER, PTS, "DEBIT", amount, "CORRECTION",
                        "rettifica di prova")).isInstanceOfSatisfying(LhException.class,
                        e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_BALANCE"));
                assertThat(w.snapshot()).as("un addebito oltre il saldo non cambia nulla").isEqualTo(snapshot);
                w.flags.add("addebito rifiutato");
                return w;
            }
            var result = w.walletService.adjustBalance(MEMBER, PTS, "DEBIT", amount, "CORRECTION", "rettifica di prova");
            assertThat(result.balanceAfter()).isEqualTo(before - amount);
            assertFifo(pre, consumption(result.ledgerEntryId()), amount, w);
            w.spent += amount;
            return w;
        }
    }

    record Spend(Fraction fraction, boolean replay) implements Action<World> {
        @Override
        public World run(World w) {
            long before = wallet(PTS).active();
            long amount = fraction.of(before);
            List<Lot> pre = activeLots(PTS);
            Snapshot snapshot = w.snapshot();
            String redemptionId = "RDM-" + (++w.counter);
            LhEvent<JsonNode> requested = w.requestedEvent(redemptionId, amount);

            w.redemptions.spend(requested);

            String spendEntry = jdbc.sql("SELECT id FROM ledger_entry WHERE redemption_id = ? AND type = 'SPEND'")
                    .param(redemptionId).query(String.class).optional().orElse(null);
            if (amount > before) {
                assertThat(spendEntry).as("tutto o niente: saldo insufficiente, nessun movimento").isNull();
                assertThat(w.snapshot()).isEqualTo(snapshot);
                w.flags.add("spesa rifiutata");
                return w;
            }
            assertThat(spendEntry).as("saldo sufficiente: la spesa avviene").isNotNull();
            assertThat(wallet(PTS).active()).isEqualTo(before - amount);
            assertFifo(pre, consumption(spendEntry), amount, w);
            w.spent += amount;
            w.spentRedemptions.put(redemptionId, amount);
            if (replay) { // RNF-03: stessa richiesta due volte = una sola spesa
                Snapshot afterFirst = w.snapshot();
                w.redemptions.spend(requested);
                assertThat(w.snapshot()).as("doppio invio = stesso stato").isEqualTo(afterFirst);
            }
            return w;
        }
    }

    record Refund(int index) implements Action<World> {
        @Override
        public boolean precondition(World w) {
            return !w.spentRedemptions.isEmpty();
        }

        @Override
        public World run(World w) {
            List<String> ids = new ArrayList<>(w.spentRedemptions.keySet());
            String redemptionId = ids.get(index % ids.size());
            long amount = w.spentRedemptions.remove(redemptionId);
            long before = wallet(PTS).active();
            Set<String> lotsBefore = new TreeSet<>(lots(MEMBER).stream().map(Lot::id).toList());
            Instant now = w.clock.instant();

            w.redemptions.refund(w.cancelledEvent(redemptionId));

            assertThat(wallet(PTS).active()).isEqualTo(before + amount);
            Lot refunded = lots(MEMBER).stream().filter(l -> !lotsBefore.contains(l.id())).findFirst().orElseThrow();
            assertThat(refunded.amount()).as("il rimborso è un lotto nuovo").isEqualTo(amount);
            assertThat(refunded.status()).isEqualTo("ACTIVE");
            if (refunded.expiresAt() != null) {
                assertThat(refunded.expiresAt()).as("rimborso: scadenza almeno oggi + 30 giorni")
                        .isAfterOrEqualTo(now.plus(30, ChronoUnit.DAYS));
            }
            w.refunded += amount;
            w.flags.add("rimborso");
            Snapshot afterFirst = w.snapshot();
            w.redemptions.refund(w.cancelledEvent(redemptionId));
            assertThat(w.snapshot()).as("doppio rimborso = stesso stato").isEqualTo(afterFirst);
            return w;
        }
    }

    record ExpireJob(Boundary boundary) implements Action<World> {
        @Override
        public World run(World w) {
            List<Lot> pre = lots(MEMBER);
            Instant nextExpiry = pre.stream().filter(l -> "ACTIVE".equals(l.status()) && l.remaining() > 0)
                    .map(Lot::expiresAt).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            Instant asOf = switch (boundary) {
                case AT_NEXT -> nextExpiry != null ? nextExpiry : w.clock.instant();
                case JUST_BEFORE_NEXT -> nextExpiry != null ? nextExpiry.minus(1, ChronoUnit.MICROS) : w.clock.instant();
                case NOW -> w.clock.instant();
            };
            if (asOf.isAfter(w.clock.instant())) {
                w.clock.set(asOf);
            }
            List<Lot> due = pre.stream().filter(l -> "ACTIVE".equals(l.status()) && l.remaining() > 0
                    && l.expiresAt() != null && !l.expiresAt().isAfter(asOf)).toList();
            long dueAmount = due.stream().mapToLong(Lot::remaining).sum();
            long before = wallet(PTS).active();

            var outcome = w.walletService.expirePoints(asOf);

            assertThat(outcome.lots()).as("lotti scaduti (expiresAt ≤ asOf, estremo incluso)").isEqualTo(due.size());
            assertThat(outcome.amount()).isEqualTo(dueAmount);
            assertThat(wallet(PTS).active()).isEqualTo(before - dueAmount);
            Map<String, Lot> post = byId(lots(MEMBER));
            for (Lot lot : pre) {
                Lot now = post.get(lot.id());
                if (due.contains(lot)) {
                    assertThat(now.status()).isEqualTo("EXPIRED");
                    assertThat(now.remaining()).isZero();
                } else {
                    assertThat(now).as("un lotto non scaduto non si tocca").isEqualTo(lot);
                }
            }
            if (!due.isEmpty()) {
                w.flags.add("lotto scaduto");
                if (boundary == Boundary.AT_NEXT) {
                    w.flags.add("scadenza all'istante esatto");
                }
            }
            if (boundary == Boundary.JUST_BEFORE_NEXT && nextExpiry != null) {
                assertThat(due).as("un microsecondo prima della scadenza il lotto vive").noneMatch(
                        l -> nextExpiry.equals(l.expiresAt()));
            }
            w.expired += dueAmount;
            assertThat(w.walletService.expirePoints(asOf).lots()).as("la scadenza è idempotente").isZero();
            return w;
        }
    }

    record ReleaseJob(Boundary boundary) implements Action<World> {
        @Override
        public World run(World w) {
            List<Lot> pre = lots(MEMBER);
            Instant nextRelease = pre.stream().filter(l -> "PENDING".equals(l.status()))
                    .map(Lot::availableAt).min(Comparator.naturalOrder()).orElse(null);
            Instant asOf = switch (boundary) {
                case AT_NEXT -> nextRelease != null ? nextRelease : w.clock.instant();
                case JUST_BEFORE_NEXT -> nextRelease != null ? nextRelease.minus(1, ChronoUnit.MICROS) : w.clock.instant();
                case NOW -> w.clock.instant();
            };
            if (asOf.isAfter(w.clock.instant())) {
                w.clock.set(asOf);
            }
            List<Lot> due = pre.stream().filter(l -> "PENDING".equals(l.status())
                    && !l.availableAt().isAfter(asOf)).toList();
            Wallet ptsBefore = wallet(PTS);

            var outcome = w.walletService.releasePending(asOf);

            assertThat(outcome.lots()).isEqualTo(due.size());
            long duePts = due.stream().filter(l -> PTS.equals(l.currency())).mapToLong(Lot::remaining).sum();
            assertThat(wallet(PTS).active()).isEqualTo(ptsBefore.active() + duePts);
            assertThat(wallet(PTS).pending()).isEqualTo(ptsBefore.pending() - duePts);
            Map<String, Lot> post = byId(lots(MEMBER));
            for (Lot lot : due) {
                assertThat(post.get(lot.id()).status()).isEqualTo("ACTIVE");
            }
            w.stsCounted += due.stream().filter(l -> STS.equals(l.currency())).mapToLong(Lot::remaining).sum();
            if (!due.isEmpty()) {
                w.flags.add("lotto rilasciato");
            }
            assertThat(w.walletService.releasePending(asOf).lots()).as("il rilascio è idempotente").isZero();
            return w;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Stato del mondo simulato (solo contatori del test: nessuna logica di dominio)
    // ------------------------------------------------------------------------------------------------------------

    static final class World {
        final MutableClock clock = new MutableClock(START);
        final WalletService walletService;
        final RedemptionPayments redemptions;
        long credited;
        long refunded;
        long spent;
        long expired;
        long stsCounted;
        int lastTierRank;
        int counter;
        final Map<String, Long> spentRedemptions = new LinkedHashMap<>();
        final Set<String> flags = new TreeSet<>();

        World() {
            LhEventFactory events = new LhEventFactory(clock, "wallet-service");
            walletService = new WalletService(wallets, ledger, tiers, memberTiers, currencies, lots, tierHistory,
                    editions, events, OUTBOX, AUDIT, MAPPER, clock);
            redemptions = new RedemptionPayments(wallets, ledger, lots, memberTiers, events, OUTBOX, MAPPER, clock);
            walletService.createWalletsForMember(MEMBER);
        }

        BigDecimal tierMultiplier() {
            return jdbc.sql("SELECT t.multiplier FROM tier t JOIN member_tier m ON m.tier_code = t.code "
                            + "WHERE m.member_id = ?").param(MEMBER).query(BigDecimal.class).single();
        }

        LhEvent<JsonNode> grantEvent(String effectId, String currency, long amount, int pendingDays,
                                     boolean tierMultiplierApplies) {
            ObjectNode d = MAPPER.createObjectNode();
            d.put("effectId", effectId);
            d.put("currency", currency);
            d.put("amount", amount);
            d.put("pendingDays", pendingDays);
            d.put("tierMultiplierApplies", tierMultiplierApplies);
            d.put("campaignCode", "CMP-PROP");
            return event(LhEventTypes.Effect.POINTS_GRANT, d);
        }

        LhEvent<JsonNode> requestedEvent(String redemptionId, long cost) {
            ObjectNode d = MAPPER.createObjectNode();
            d.put("redemptionId", redemptionId);
            d.put("pointsCost", cost);
            d.put("rewardCode", "RWD-PROP");
            d.put("rewardName", "Premio di prova");
            return event(LhEventTypes.Fact.REWARD_REDEMPTION_REQUESTED, d);
        }

        LhEvent<JsonNode> cancelledEvent(String redemptionId) {
            ObjectNode d = MAPPER.createObjectNode();
            d.put("redemptionId", redemptionId);
            d.put("refund", true);
            d.put("reason", "prova");
            return event(LhEventTypes.Fact.REWARD_REDEMPTION_CANCELLED, d);
        }

        private LhEvent<JsonNode> event(String type, JsonNode data) {
            String id = Ulid.next(clock);
            return new LhEvent<>(LhEvent.SPEC_VERSION, id, "urn:loyaltyhub:service:prop", type, "member:" + MEMBER,
                    clock.instant(), LhEvent.DATA_CONTENT_TYPE, null, LhEvent.TENANT, id, null, 0, null, data);
        }

        Snapshot snapshot() {
            return new Snapshot(lots(MEMBER), wallet(PTS), wallet(STS),
                    jdbc.sql("SELECT count(*) FROM ledger_entry").query(Long.class).single());
        }

        /** Invarianti del libro mastro, controllate dopo ogni azione della sequenza. */
        void checkInvariants() {
            List<Lot> all = lots(MEMBER);
            Map<String, Long> consumedByLot = jdbc.sql("SELECT lot_id, sum(amount) AS n FROM lot_consumption "
                            + "GROUP BY lot_id").query((rs, i) -> Map.entry(rs.getString("lot_id"), rs.getLong("n")))
                    .list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Map<String, Wallet> byCurrency = wallets();
            Map<String, Long> lastBalanceAfter = new LinkedHashMap<>();
            jdbc.sql("SELECT DISTINCT ON (currency) currency, balance_after FROM ledger_entry WHERE member_id = ? "
                            + "ORDER BY currency, id DESC").param(MEMBER)
                    .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                    .forEach(e -> lastBalanceAfter.put(e.getKey(), e.getValue()));
            for (String currency : List.of(PTS, STS)) {
                Wallet wl = byCurrency.get(currency);
                long active = all.stream().filter(l -> currency.equals(l.currency()) && "ACTIVE".equals(l.status()))
                        .mapToLong(Lot::remaining).sum();
                long pending = all.stream().filter(l -> currency.equals(l.currency()) && "PENDING".equals(l.status()))
                        .mapToLong(Lot::remaining).sum();
                assertThat(wl.active()).as("%s: saldo attivo = Σ residui dei lotti ACTIVE", currency).isEqualTo(active);
                assertThat(wl.pending()).as("%s: in attesa = Σ lotti PENDING", currency).isEqualTo(pending);
                assertThat(wl.active()).as("%s: saldo mai negativo", currency).isGreaterThanOrEqualTo(0);
                if (lastBalanceAfter.containsKey(currency)) {
                    assertThat(lastBalanceAfter.get(currency)).as("%s: l'ultimo movimento chiude sul saldo", currency)
                            .isEqualTo(wl.active());
                }
            }

            for (Lot lot : all) {
                assertThat(lot.remaining()).as("lotto %s: residuo non negativo", lot.id()).isGreaterThanOrEqualTo(0);
                assertThat(lot.remaining()).isLessThanOrEqualTo(lot.amount());
                long consumed = consumedByLot.getOrDefault(lot.id(), 0L);
                switch (lot.status()) {
                    case "ACTIVE" -> assertThat(lot.remaining()).isPositive();
                    case "PENDING" -> assertThat(lot.remaining()).isEqualTo(lot.amount());
                    case "EXHAUSTED", "EXPIRED" -> assertThat(lot.remaining()).isZero();
                    default -> throw new AssertionError("stato di lotto sconosciuto: " + lot.status());
                }
                if (!"EXPIRED".equals(lot.status())) {
                    assertThat(lot.amount() - lot.remaining()).as("lotto %s: speso = Σ lot_consumption", lot.id())
                            .isEqualTo(consumed);
                } else {
                    assertThat(consumed).isLessThanOrEqualTo(lot.amount());
                }
            }

            // Conservazione dei punti: accreditato + rimborsato = speso + scaduto + (attivo + in attesa).
            Wallet pts = byCurrency.get(PTS);
            assertThat(credited + refunded - spent - expired).as("accreditato + rimborsato − speso − scaduto = saldo")
                    .isEqualTo(pts.active() + pts.pending());
            assertThat(pts.lifetimeEarned()).isEqualTo(credited);
            assertThat(pts.lifetimeSpent()).isEqualTo(spent - refunded);
            assertThat(pts.lifetimeExpired()).isEqualTo(expired);
            long expiredFromLots = all.stream().filter(l -> PTS.equals(l.currency()) && "EXPIRED".equals(l.status()))
                    .mapToLong(l -> l.amount() - consumedByLot.getOrDefault(l.id(), 0L)).sum();
            assertThat(expiredFromLots).as("scaduto = Σ residui azzerati dei lotti EXPIRED").isEqualTo(expired);

            // Ogni spesa e ogni addebito hanno consumato esattamente il loro importo, dai lotti.
            long mismatched = jdbc.sql("""
                    SELECT count(*) FROM ledger_entry e
                    WHERE e.type IN ('SPEND', 'ADJUST_DEBIT')
                      AND e.amount <> coalesce((SELECT sum(c.amount) FROM lot_consumption c
                                                WHERE c.ledger_entry_id = e.id), 0)
                    """).query(Long.class).single();
            assertThat(mismatched).as("movimenti di uscita senza consumi coerenti").isZero();

            // Punti status e livello: mai in discesa durante l'edizione, sempre il più alto raggiunto.
            var tier = jdbc.sql("SELECT tier_code, period_sts FROM member_tier WHERE member_id = ?").param(MEMBER)
                    .query((rs, i) -> Tuple.of(rs.getString("tier_code"), rs.getLong("period_sts"))).single();
            assertThat(tier.get2()).as("punti status dell'edizione").isEqualTo(stsCounted);
            List<Tuple.Tuple3<String, Integer, Long>> scale = jdbc.sql("SELECT code, rank, threshold_sts FROM tier "
                            + "ORDER BY rank").query((rs, i) -> Tuple.of(rs.getString("code"), rs.getInt("rank"),
                    rs.getLong("threshold_sts"))).list();
            var expectedTier = scale.stream().filter(t -> t.get3() <= tier.get2()).max(
                    Comparator.comparingInt(Tuple.Tuple3::get2)).orElseThrow();
            assertThat(tier.get1()).as("livello = il più alto con soglia ≤ punti status").isEqualTo(expectedTier.get1());
            int rank = expectedTier.get2();
            assertThat(rank).as("il livello non scende mai in corso d'anno").isGreaterThanOrEqualTo(lastTierRank);
            if (rank > lastTierRank) {
                flags.add("livello salito");
            }
            lastTierRank = rank;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Letture dirette (SQL del test, indipendenti dai repository di produzione)
    // ------------------------------------------------------------------------------------------------------------

    record Lot(String id, String currency, String status, long amount, long remaining, Instant earnedAt,
               Instant availableAt, Instant expiresAt, String ledgerEntryId) {
    }

    record Wallet(long active, long pending, long lifetimeEarned, long lifetimeSpent, long lifetimeExpired) {
    }

    /** Stato osservabile del membro: usato per «nessun cambiamento» (rifiuti e doppi invii). */
    record Snapshot(List<Lot> lots, Wallet pts, Wallet sts, long ledgerEntries) {
    }

    static List<Lot> lots(String memberId) {
        return jdbc.sql("SELECT id, currency, status, amount, remaining, earned_at, available_at, expires_at, "
                        + "ledger_entry_id FROM points_lot WHERE member_id = ? ORDER BY id").param(memberId)
                .query((rs, i) -> new Lot(rs.getString("id"), rs.getString("currency"), rs.getString("status"),
                        rs.getLong("amount"), rs.getLong("remaining"), instant(rs.getTimestamp("earned_at")),
                        instant(rs.getTimestamp("available_at")), instant(rs.getTimestamp("expires_at")),
                        rs.getString("ledger_entry_id"))).list();
    }

    static List<Lot> activeLots(String currency) {
        return lots(MEMBER).stream()
                .filter(l -> currency.equals(l.currency()) && "ACTIVE".equals(l.status()) && l.remaining() > 0).toList();
    }

    static Wallet wallet(String currency) {
        return wallets().get(currency);
    }

    static Map<String, Wallet> wallets() {
        Map<String, Wallet> out = new LinkedHashMap<>();
        jdbc.sql("SELECT currency, balance_active, balance_pending, lifetime_earned, lifetime_spent, lifetime_expired "
                        + "FROM wallet WHERE member_id = ?").param(MEMBER)
                .query((rs, i) -> Map.entry(rs.getString(1),
                        new Wallet(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6))))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    static Map<String, Long> consumption(String ledgerEntryId) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT lot_id, amount FROM lot_consumption WHERE ledger_entry_id = ?").param(ledgerEntryId)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static Map<String, Lot> byId(List<Lot> all) {
        Map<String, Lot> out = new LinkedHashMap<>();
        all.forEach(l -> out.put(l.id(), l));
        return out;
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /**
     * FIFO (docs/03 §4.2): dalle istantanee dei lotti attivi prima della spesa, i lotti che scadono prima (null per
     * ultimi), poi quelli guadagnati prima, vengono svuotati per intero prima di toccare i successivi; a parità di
     * scadenza e di data di guadagno l'ordine non è specificato. Somma dei consumi = importo, mai oltre il residuo.
     */
    static void assertFifo(List<Lot> pre, Map<String, Long> taken, long amount, World w) {
        assertThat(taken.values().stream().mapToLong(Long::longValue).sum()).as("Σ consumi = importo").isEqualTo(amount);
        Map<String, Lot> preById = byId(pre);
        taken.forEach((id, n) -> {
            assertThat(preById).as("consumo da un lotto attivo").containsKey(id);
            assertThat(n).isPositive().isLessThanOrEqualTo(preById.get(id).remaining());
        });
        Comparator<Instant> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Map<List<Instant>, List<Lot>> classes = new java.util.TreeMap<>((a, b) -> {
            int c = nullsLast.compare(a.get(0), b.get(0));
            return c != 0 ? c : a.get(1).compareTo(b.get(1));
        });
        for (Lot lot : pre) {
            classes.computeIfAbsent(java.util.Arrays.asList(lot.expiresAt(), lot.earnedAt()), k -> new ArrayList<>())
                    .add(lot);
        }
        boolean frontier = false;
        int touchedClasses = 0;
        for (List<Lot> cls : classes.values()) {
            long remaining = cls.stream().mapToLong(Lot::remaining).sum();
            long consumed = cls.stream().mapToLong(l -> taken.getOrDefault(l.id(), 0L)).sum();
            if (frontier) {
                assertThat(consumed).as("FIFO: nessun lotto successivo prima di aver svuotato i precedenti").isZero();
            } else if (consumed < remaining) {
                frontier = true;
            }
            if (consumed > 0) {
                touchedClasses++;
            }
        }
        if (touchedClasses > 1) {
            w.flags.add("spesa su più lotti");
        }
    }

    /** Orologio fisso a istanti scelti dal test (convenzione di docs/06 §9), spostato solo in avanti dalle azioni. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
