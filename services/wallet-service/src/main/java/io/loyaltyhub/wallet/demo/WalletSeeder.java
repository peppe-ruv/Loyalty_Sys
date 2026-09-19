package io.loyaltyhub.wallet.demo;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import io.loyaltyhub.wallet.infra.EditionRepository;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.loyaltyhub.wallet.domain.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    public WalletSeeder(SeedLoader seed, CurrencyRepository currencies, TierRepository tiers,
                        EditionRepository editions, WalletRepository wallets, MemberTierRepository memberTiers,
                        LedgerRepository ledger) {
        this.seed = seed;
        this.currencies = currencies;
        this.tiers = tiers;
        this.editions = editions;
        this.wallets = wallets;
        this.memberTiers = memberTiers;
        this.ledger = ledger;
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
        for (JsonNode w : seed.readTree("wallets.json")) {
            String memberId = w.path("memberId").asString();
            JsonNode balances = w.path("balances");
            long pts = balances.path("PTS").asLong(0);
            long sts = balances.path("STS").asLong(0);
            wallets.setBalance(memberId, "PTS", pts, pts);
            wallets.setBalance(memberId, "STS", sts, sts);
            memberTiers.set(memberId, w.path("tier").asString("BASE"), w.path("periodSts").asLong(0));
        }
        log.info("Seed wallet caricato (profilo demo): valute, livelli, edizioni, saldi");
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
