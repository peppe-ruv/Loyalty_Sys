package io.loyaltyhub.reward.application;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.MemberSnapshot;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import io.loyaltyhub.reward.infra.CatalogRepository;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catalogo del portale (docs/servizi/reward-service.md §3, docs/03 §5): solo premi {@code LIVE} e in validità, divisi
 * per fascia. I premi riservati ad altri tier restano visibili con {@code lockedByTier}; quelli fuori segmento sono
 * esclusi. Il saldo non si conosce qui: "raggiungibile" lo calcola il frontend incrociando catalogo e wallet.
 */
@Service
public class PortalCatalogService {

    public record LockedByTier(List<String> requiredTiers) {
    }

    public record PortalReward(String code, String name, String type, String imageUrl, String category,
                               long pointsCost, String stockState, LockedByTier lockedByTier,
                               boolean perMemberLimitReached) {
    }

    public record PortalBand(String code, String name, long pointsThreshold, String color, List<PortalReward> rewards) {
    }

    public record PortalCatalog(List<PortalBand> bands) {
    }

    public record PortalRewardDetail(String code, String name, String description, String terms, String type,
                                     String imageUrl, String category, String band, long pointsCost, String stockState,
                                     Integer stockRemaining, LockedByTier lockedByTier, boolean perMemberLimitReached,
                                     Integer perMemberLimit) {
    }

    private final CatalogRepository catalog;
    private final RewardRepository rewards;
    private final MemberSnapshotRepository members;
    private final RedemptionRepository redemptions;
    private final Clock clock;

    public PortalCatalogService(CatalogRepository catalog, RewardRepository rewards, MemberSnapshotRepository members,
                                RedemptionRepository redemptions, Clock clock) {
        this.catalog = catalog;
        this.rewards = rewards;
        this.members = members;
        this.redemptions = redemptions;
        this.clock = clock;
    }

    public PortalCatalog catalog(String memberId) {
        MemberSnapshot member = member(memberId);
        Instant now = clock.instant();
        Map<String, List<Reward>> byBand = rewards.search(RewardStatus.LIVE.name(), null, null, null, null).stream()
                .filter(r -> r.validAt(now) && inSegment(r, member))
                .collect(Collectors.groupingBy(Reward::bandCode));
        List<PortalBand> bands = new ArrayList<>();
        for (Band b : catalog.bands()) {
            List<PortalReward> items = byBand.getOrDefault(b.code(), List.of()).stream()
                    .map(r -> new PortalReward(r.code(), r.name(), r.type(), r.imageUrl(), r.categoryCode(),
                            b.pointsThreshold(), stockState(r), lock(r, member), limitReached(r, memberId)))
                    .toList();
            bands.add(new PortalBand(b.code(), b.name(), b.pointsThreshold(), b.color(), items));
        }
        return new PortalCatalog(bands);
    }

    public PortalRewardDetail detail(String code, String memberId) {
        MemberSnapshot member = member(memberId);
        Reward r = rewards.findByCode(code)
                .filter(x -> x.status() == RewardStatus.LIVE && x.validAt(clock.instant()) && inSegment(x, member))
                .orElseThrow(() -> LhException.notFound("Premio non disponibile: " + code));
        Band b = catalog.band(r.bandCode()).orElseThrow();
        return new PortalRewardDetail(r.code(), r.name(), r.description(), r.terms(), r.type(), r.imageUrl(),
                r.categoryCode(), b.code(), b.pointsThreshold(), stockState(r), r.stockRemaining(), lock(r, member),
                limitReached(r, memberId), r.perMemberLimit());
    }

    /** {@code AVAILABLE}, {@code LOW} (sotto il 10 % del totale) o {@code SOLD_OUT}; illimitato = sempre disponibile. */
    public static String stockState(Reward r) {
        if (r.unlimited()) {
            return "AVAILABLE";
        }
        int remaining = r.stockRemaining() == null ? 0 : r.stockRemaining();
        if (remaining <= 0) {
            return "SOLD_OUT";
        }
        return remaining * 10 < r.stockTotal() ? "LOW" : "AVAILABLE";
    }

    static LockedByTier lock(Reward r, MemberSnapshot m) {
        if (r.eligibleTiers().isEmpty() || (m != null && r.eligibleTiers().contains(m.tierCode()))) {
            return null;
        }
        return new LockedByTier(r.eligibleTiers());
    }

    /** Premi con segmenti ammessi: visibili solo ai membri che ne fanno parte (segmenti popolati da M6). */
    static boolean inSegment(Reward r, MemberSnapshot m) {
        if (r.eligibleSegments().isEmpty()) {
            return true;
        }
        return m != null && m.segments().stream().anyMatch(r.eligibleSegments()::contains);
    }

    private boolean limitReached(Reward r, String memberId) {
        return r.perMemberLimit() != null && memberId != null
                && redemptions.countActive(memberId, r.code()) >= r.perMemberLimit();
    }

    private MemberSnapshot member(String memberId) {
        return memberId == null || memberId.isBlank() ? null : members.find(memberId).orElse(null);
    }
}
