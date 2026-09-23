package io.loyaltyhub.reward.demo;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Category;
import io.loyaltyhub.reward.domain.CouponPool;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import io.loyaltyhub.reward.infra.CatalogRepository;
import io.loyaltyhub.reward.infra.CouponRepository;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carica categorie, fasce, pool coupon e premi dai seed (docs/servizi/reward-service.md §6, docs/10 §5) e lo snapshot dei membri
 * da {@code members.json} + {@code wallets.json} (livello). Profilo {@code demo}, ripetibile via {@code /v1/demo/reset}.
 */
@Component
@Profile("demo")
public class RewardSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(RewardSeeder.class);

    private final SeedLoader seed;
    private final CatalogRepository catalog;
    private final RewardRepository rewards;
    private final RedemptionRepository redemptions;
    private final MemberSnapshotRepository members;
    private final CouponRepository coupons;
    private final CouponService couponService;
    private final Clock clock;

    public RewardSeeder(SeedLoader seed, CatalogRepository catalog, RewardRepository rewards,
                        RedemptionRepository redemptions, MemberSnapshotRepository members, CouponRepository coupons,
                        CouponService couponService, Clock clock) {
        this.seed = seed;
        this.catalog = catalog;
        this.rewards = rewards;
        this.redemptions = redemptions;
        this.members = members;
        this.coupons = coupons;
        this.couponService = couponService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "reward";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        redemptions.deleteAll();
        rewards.deleteAll();
        coupons.deleteAll();
        catalog.deleteAll();
        members.deleteAll();

        for (JsonNode c : seed.readTree("reward-categories.json")) {
            catalog.upsertCategory(new Category(c.path("code").asString(), c.path("name").asString(),
                    text(c, "icon"), c.path("sortOrder").asInt(0)));
        }
        for (JsonNode b : seed.readTree("reward-bands.json")) {
            catalog.upsertBand(new Band(b.path("code").asString(), b.path("name").asString(),
                    b.path("pointsThreshold").asLong(), text(b, "color"), b.path("sortOrder").asInt(0)));
        }
        // Pool coupon: seme stabile dal codice → stessi codici a ogni reset (docs/10 §1); lo storico consumato
        // prima della demo è marcato USED.
        Map<String, String> poolIds = new HashMap<>();
        for (JsonNode p : seed.readTree("coupon-pools.json")) {
            String code = p.path("code").asString();
            CouponPool pool = new CouponPool(Ulid.next(clock), code, p.path("name").asString(), p.path("prefix").asString(),
                    p.path("validityDays").asInt(90), CouponService.seedFor(code), null);
            coupons.insertPool(pool);
            poolIds.put(code, pool.id());
            int size = p.path("size").asInt(0);
            if (size > 0) {
                couponService.generate(pool.id(), size, false);
            }
            int consumed = p.path("consumed").asInt(0);
            if (consumed > 0) {
                coupons.seedConsume(pool.id(), consumed, clock.instant().minus(java.time.Duration.ofDays(30)));
            }
        }
        for (JsonNode r : seed.readTree("rewards.json")) {
            rewards.insert(new Reward(Ulid.next(clock), r.path("code").asString(), r.path("name").asString(),
                    text(r, "description"), text(r, "terms"), text(r, "imageUrl"), r.path("type").asString(),
                    text(r, "category"), r.path("band").asString(), r.path("fulfilment").asString(),
                    r.hasNonNull("couponPool") ? poolIds.get(r.get("couponPool").asString()) : null,
                    intOrNull(r, "stockTotal"), intOrNull(r, "stockRemaining"), intOrNull(r, "perMemberLimit"),
                    strings(r.path("eligibleTiers")), strings(r.path("eligibleSegments")),
                    date(r, "validFrom"), date(r, "validTo"), RewardStatus.valueOf(r.path("status").asString("DRAFT")),
                    0, "seed", null));
        }
        Map<String, String> tiers = new HashMap<>();
        for (JsonNode w : seed.readTree("wallets.json")) {
            tiers.put(w.path("memberId").asString(), w.path("tier").asString("BASE"));
        }
        for (JsonNode m : seed.readTree("members.json")) {
            String id = m.path("id").asString();
            members.seed(id, m.path("status").asString("ACTIVE"), tiers.getOrDefault(id, "BASE"),
                    text(m, "firstName"), text(m, "lastName"));
        }
        log.info("Seed reward caricato (profilo demo): categorie, fasce, pool coupon, premi, snapshot membri");
    }

    private Instant date(JsonNode n, String field) {
        return n.hasNonNull(field) ? SeedDates.resolve(n.get(field).asString(), clock) : null;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asInt() : null;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asString()));
        return out;
    }
}
