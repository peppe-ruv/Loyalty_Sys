package io.loyaltyhub.reward.demo;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalStatus;
import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.reward.application.CouponService;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Category;
import io.loyaltyhub.reward.domain.CouponPool;
import io.loyaltyhub.reward.domain.Redemption;
import io.loyaltyhub.reward.domain.RedemptionStatus;
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
 * Carica categorie, fasce, pool coupon, premi e richieste d'esempio dai seed (docs/servizi/reward-service.md §6, docs/10 §5) e lo snapshot dei membri
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
    private final ApprovalHistoryStore approvalHistory;
    private final Clock clock;

    public RewardSeeder(SeedLoader seed, CatalogRepository catalog, RewardRepository rewards,
                        RedemptionRepository redemptions, MemberSnapshotRepository members, CouponRepository coupons,
                        CouponService couponService, ApprovalHistoryStore approvalHistory, Clock clock) {
        this.seed = seed;
        this.catalog = catalog;
        this.rewards = rewards;
        this.redemptions = redemptions;
        this.members = members;
        this.coupons = coupons;
        this.couponService = couponService;
        this.approvalHistory = approvalHistory;
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
        approvalHistory.deleteAll(ApprovalPolicy.REWARD);
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
        // Premi in revisione nel seed: nella coda approvazioni (BO-21) dal giorno prima, inviati dal marketing.
        for (Reward r : rewards.search(RewardStatus.IN_REVIEW.name(), null, null, null, null)) {
            approvalHistory.record(ApprovalPolicy.REWARD, r.id(), ApprovalStatus.DRAFT, ApprovalStatus.IN_REVIEW,
                    ApprovalAction.SUBMIT, "MARKETING:luca.marketing", null,
                    clock.instant().minus(java.time.Duration.ofDays(1)));
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
        seedRedemptions();
        log.info("Seed reward caricato (profilo demo): categorie, fasce, pool coupon, premi, snapshot membri, richieste");
    }

    /**
     * Storico delle richieste (docs/10 §5, {@code redemptions.json}): stato, cronologia coerente con lo stato e, per le
     * evase con coupon, un codice del pool nello stato indicato. Lo stock dei premi è già al netto (rewards.json).
     */
    private void seedRedemptions() {
        Map<String, Reward> byCode = new HashMap<>();
        rewards.findAll().forEach(r -> byCode.put(r.code(), r));
        Map<String, Integer> validity = new HashMap<>();
        coupons.pools().forEach(p -> validity.put(p.id(), p.validityDays()));
        for (JsonNode x : seed.readTree("redemptions.json")) {
            String id = x.path("id").asString();
            String memberId = x.path("memberId").asString();
            Reward reward = byCode.get(x.path("rewardCode").asString());
            RedemptionStatus status = RedemptionStatus.valueOf(x.path("status").asString());
            Instant requested = date(x, "requestedAt");
            boolean spent = status == RedemptionStatus.CONFIRMED || status == RedemptionStatus.FULFILLED
                    || (status == RedemptionStatus.CANCELLED && x.path("refund").asBoolean(false));
            Instant confirmed = spent ? requested.plusSeconds(2) : null;
            Instant closed = status == RedemptionStatus.CONFIRMED ? null
                    : x.hasNonNull("closedAt") ? date(x, "closedAt") : requested.plusSeconds(3);
            String couponCode = null;
            JsonNode c = x.path("coupon");
            if (!c.isMissingNode() && reward.couponPoolId() != null) {
                couponCode = coupons.takeAvailable(reward.couponPoolId()).orElse(null);
                if (couponCode != null) {
                    Instant expires = c.hasNonNull("expiresAt") ? date(c, "expiresAt")
                            : CouponService.expiryFor(closed, validity.getOrDefault(reward.couponPoolId(), 90)); // Q-277
                    coupons.markIssued(couponCode, memberId, reward.code(), "REDEMPTION", id, null, closed, expires);
                    switch (c.path("status").asString("ISSUED")) {
                        case "USED" -> coupons.markUsed(couponCode, closed.plus(java.time.Duration.ofDays(2)));
                        case "EXPIRED" -> coupons.markExpired(couponCode);
                        default -> { }
                    }
                }
            }
            String reason = status == RedemptionStatus.REJECTED || status == RedemptionStatus.CANCELLED ? text(x, "reason") : null;
            redemptions.seed(new Redemption(id, memberId, reward.code(), reward.name(), x.path("pointsCost").asLong(),
                    status, reason, x.path("needsAttention").asBoolean(false), couponCode, text(x, "note"),
                    x.hasNonNull("shipping") ? x.get("shipping").toString() : null, "SEED-" + id, requested,
                    confirmed, closed, "MEMBER:" + memberId));
            redemptions.addHistory(Ulid.next(clock), id, RedemptionStatus.PENDING,
                    "Richiesta di " + x.path("pointsCost").asLong() + " PTS", "MEMBER:" + memberId, requested);
            if (confirmed != null) {
                redemptions.addHistory(Ulid.next(clock), id, RedemptionStatus.CONFIRMED,
                        "Punti spesi: " + x.path("pointsCost").asLong() + " PTS", "SYSTEM", confirmed);
            }
            if (x.path("needsAttention").asBoolean(false)) {
                redemptions.addHistory(Ulid.next(clock), id, RedemptionStatus.CONFIRMED,
                        "Pool coupon esaurito: da evadere dopo una nuova generazione", "SYSTEM", confirmed.plusSeconds(1));
            }
            switch (status) {
                case FULFILLED -> redemptions.addHistory(Ulid.next(clock), id, status,
                        couponCode != null ? "Coupon emesso: " + couponCode : x.hasNonNull("note") ? "Evasa: " + text(x, "note") : "Evasa",
                        couponCode != null || !x.hasNonNull("note") ? "SYSTEM" : "CARE:paolo.care", closed);
                case REJECTED -> redemptions.addHistory(Ulid.next(clock), id, status, "Respinta: " + reason, "SYSTEM", closed);
                case CANCELLED -> redemptions.addHistory(Ulid.next(clock), id, status,
                        x.path("refund").asBoolean(false) ? "Annullata con rimborso: " + reason : "Annullata dal membro",
                        x.path("refund").asBoolean(false) ? "CARE:paolo.care" : "MEMBER:" + memberId, closed);
                default -> { }
            }
        }
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
