package io.loyaltyhub.wallet.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import io.loyaltyhub.common.ids.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carica valute, livelli, edizioni e i saldi iniziali dei wallet dai seed (docs/servizi/wallet-service.md §6).
 * Attivo col profilo {@code demo}, idempotente, ripetibile via {@code POST /v1/demo/reset}. Lotti e movimenti
 * storici arrivano con M3: qui i saldi sono impostati direttamente da {@code seed/wallets.json}.
 */
@Component
@Profile("demo")
public class WalletSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(WalletSeeder.class);

    private final SeedLoader seed;
    private final CurrencyRepository currencies;
    private final TierRepository tiers;
    private final EditionRepository editions;
    private final WalletRepository wallets;
    private final MemberTierRepository memberTiers;
    private final LedgerRepository ledger;
    private final PointsLotRepository lots;
    private final TierHistoryRepository tierHistory;
    private final ObjectMapper mapper;
    private final Clock clock;

    public WalletSeeder(SeedLoader seed, CurrencyRepository currencies, TierRepository tiers,
                        EditionRepository editions, WalletRepository wallets, MemberTierRepository memberTiers,
                        LedgerRepository ledger, PointsLotRepository lots, TierHistoryRepository tierHistory,
                        ObjectMapper mapper, Clock clock) {
        this.seed = seed;
        this.currencies = currencies;
        this.tiers = tiers;
        this.editions = editions;
        this.wallets = wallets;
        this.memberTiers = memberTiers;
        this.ledger = ledger;
        this.lots = lots;
        this.tierHistory = tierHistory;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "wallet";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        tierHistory.deleteAll();
        lots.deleteAll();
        ledger.deleteAll();
        wallets.deleteAll();
        memberTiers.deleteAll();

        for (JsonNode c : seed.readTree("currencies.json")) {
            currencies.upsert(c.path("code").asString(), c.path("name").asString(),
                    c.path("spendable").asBoolean(true),
                    c.has("expiryPolicy") ? c.get("expiryPolicy").toString() : "{}");
        }
        for (JsonNode t : seed.readTree("tiers.json")) {
            List<String> benefits = new ArrayList<>();
            t.path("benefits").forEach(b -> benefits.add(b.asString()));
            Tier tier = new Tier(t.path("code").asString(), t.path("name").asString(), t.path("rank").asInt(),
                    t.path("thresholdSts").asLong(0), t.path("multiplier").decimalValue(), benefits,
                    text(t, "color"), text(t, "icon"));
            tiers.upsert(tier, t.path("benefits").toString());
        }
        for (JsonNode e : seed.readTree("editions.json")) {
            editions.upsert(e.path("code").asString(), e.path("name").asString(),
                    LocalDate.parse(e.path("startDate").asString()), LocalDate.parse(e.path("endDate").asString()),
                    e.hasNonNull("redemptionGraceUntil") ? LocalDate.parse(e.get("redemptionGraceUntil").asString()) : null,
                    e.path("status").asString("PLANNED"));
        }
        JsonNode ptsPolicy = currencies.findByCode("PTS")
                .map(c -> parse(c.expiryPolicyJson())).orElse(null);
        for (JsonNode w : seed.readTree("wallets.json")) {
            String memberId = w.path("memberId").asString();
            JsonNode balances = w.path("balances");
            long pts = balances.path("PTS").asLong(0);
            long sts = balances.path("STS").asLong(0);
            wallets.setBalance(memberId, "PTS", pts, pts);
            wallets.setBalance(memberId, "STS", sts, sts);
            String tier = w.path("tier").asString("BASE");
            memberTiers.set(memberId, tier, w.path("periodSts").asLong(0));
            // Storico livelli minimo: la qualifica iniziale, così BO-07/tier-history non è vuota (docs §6).
            tierHistory.insert(new TierHistory(Ulid.next(clock), memberId, null, tier,
                    TierHistory.INITIAL, null, clock.instant().minus(90, ChronoUnit.DAYS)));
            // expiringSoon (opzionale, docs/10 §3): PTS in scadenza a breve per il personaggio (Chiara: 1 900).
            Long soon = w.hasNonNull("expiringSoon") ? w.get("expiringSoon").asLong() : null;
            seedLots(memberId, "PTS", pts, ptsPolicy, soon);
            seedLots(memberId, "STS", sts, null, null); // STS: policy EDITION, senza scadenza in M3.1
        }
        // Stato del membro dal seed dell'anagrafica (in esercizio arriva da member.status.changed): un membro
        // BLOCKED non spende punti nella saga di richiesta premio (docs/03 §5).
        for (JsonNode m : seed.readTree("members.json")) {
            String status = m.path("status").asString("ACTIVE");
            if (!"ACTIVE".equals(status)) {
                memberTiers.updateStatus(m.path("id").asString(), status);
            }
        }
        seedRedemptionHistory();
        log.info("Seed wallet caricato (profilo demo): valute, livelli, edizioni, saldi, lotti, stato membri, spese premi");
    }

    /**
     * Movimenti delle richieste premio d'esempio ({@code redemptions.json}, docs/10 §5): {@code SPEND} per le richieste
     * che hanno speso punti e {@code REFUND} per quelle annullate con rimborso, legati a {@code redemption_id} così un
     * annullo da BO-13 trova la spesa da rimborsare. Il saldo seminato è già al netto: il saldo dopo ogni movimento
     * si ricostruisce all'indietro dal saldo attuale (nello storico seminato non ci sono altri movimenti).
     */
    private void seedRedemptionHistory() {
        record Movement(String memberId, String redemptionId, String type, long amount, Instant at) {
        }
        List<Movement> moves = new ArrayList<>();
        for (JsonNode x : seed.readTree("redemptions.json")) {
            String status = x.path("status").asString();
            boolean refunded = "CANCELLED".equals(status) && x.path("refund").asBoolean(false);
            if (!"CONFIRMED".equals(status) && !"FULFILLED".equals(status) && !refunded) {
                continue;
            }
            Instant requested = SeedDates.resolve(x.path("requestedAt").asString(), clock);
            long cost = x.path("pointsCost").asLong();
            moves.add(new Movement(x.path("memberId").asString(), x.path("id").asString(), "SPEND", cost, requested.plusSeconds(1)));
            if (refunded) {
                Instant closed = x.hasNonNull("closedAt") ? SeedDates.resolve(x.get("closedAt").asString(), clock) : requested.plusSeconds(3);
                moves.add(new Movement(x.path("memberId").asString(), x.path("id").asString(), "REFUND", cost, closed));
            }
        }
        moves.sort(Comparator.comparing(Movement::at).reversed());
        Map<String, Long> running = new HashMap<>();
        for (Movement m : moves) {
            long after = running.computeIfAbsent(m.memberId(),
                    id -> wallets.find(id, "PTS").map(w -> w.balanceActive()).orElse(0L));
            boolean spend = "SPEND".equals(m.type());
            ledger.insert(new LedgerEntry(Ulid.next(clock), m.memberId(), "PTS", m.type(), m.amount(), spend ? "-" : "+", after,
                            m.at(), "REDEMPTION", null, spend ? "Premio (storico demo)" : "Rimborso richiesta premio", "{}"),
                    null, null, "SEED-" + m.redemptionId(), "SEED", m.redemptionId());
            running.put(m.memberId(), spend ? after + m.amount() : after - m.amount());
        }
    }

    /**
     * Crea lotti {@code ACTIVE} la cui somma dei {@code remaining} è il saldo (invariante docs §6). Per i punti
     * spendibili li scaglia su date diverse così alcuni scadono entro 30 giorni: le schede scadenze (PT-07,
     * BO-30) hanno dati appena accesa la demo. Le date sono relative a oggi (docs §6).
     */
    private void seedLots(String memberId, String currency, long balance, JsonNode policy, Long expiringSoon) {
        if (balance <= 0) {
            return;
        }
        Instant now = clock.instant();
        // Punti spendibili di taglio ≥ 100: una quota "in scadenza entro 12 giorni" perché le schede scadenze
        // (PT-07, BO-30) non siano mai vuote, il resto guadagnato di recente (scadenza ~12 mesi dalla policy).
        if (currency.equals("PTS") && balance >= 100) {
            long soon = expiringSoon != null ? Math.min(expiringSoon, balance) : balance * 30 / 100;
            lots.insert(new PointsLot(Ulid.next(clock), memberId, currency, soon, soon, PointsLot.ACTIVE,
                    now.minus(353, ChronoUnit.DAYS), null, now.plus(12, ChronoUnit.DAYS), null));
            long rest = balance - soon;
            Instant earnedAt = now.minus(60, ChronoUnit.DAYS);
            lots.insert(new PointsLot(Ulid.next(clock), memberId, currency, rest, rest, PointsLot.ACTIVE,
                    earnedAt, null, ExpiryPolicy.expiresAt(policy, earnedAt), null));
        } else {
            Instant earnedAt = now.minus(60, ChronoUnit.DAYS);
            lots.insert(new PointsLot(Ulid.next(clock), memberId, currency, balance, balance, PointsLot.ACTIVE,
                    earnedAt, null, ExpiryPolicy.expiresAt(policy, earnedAt), null));
        }
    }

    private JsonNode parse(String json) {
        return json == null || json.isBlank() ? null : mapper.readTree(json);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
