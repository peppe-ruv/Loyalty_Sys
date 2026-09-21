package io.loyaltyhub.wallet.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.domain.Tier;
import io.loyaltyhub.wallet.domain.TierHistory;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.TierHistoryRepository;
import io.loyaltyhub.wallet.infra.TierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Amministrazione dei livelli (BO-07, F-TIER-01): distribuzione, modifica con vincoli e storico. La modifica
 * mantiene le soglie strettamente crescenti col rank ({@code TIER_THRESHOLDS_NOT_MONOTONIC}) e {@code BASE} a 0.
 */
@Service
public class TierAdminService {

    private final TierRepository tiers;
    private final MemberTierRepository memberTiers;
    private final TierHistoryRepository history;
    private final AuditPublisher audit;

    public TierAdminService(TierRepository tiers, MemberTierRepository memberTiers,
                            TierHistoryRepository history, AuditPublisher audit) {
        this.tiers = tiers;
        this.memberTiers = memberTiers;
        this.history = history;
        this.audit = audit;
    }

    public record TierCount(String code, String name, int rank, long threshold, BigDecimal multiplier, long members) {
    }

    public record TierUpdate(String name, Long thresholdSts, BigDecimal multiplier,
                             List<String> benefits, String color, String icon) {
    }

    public List<TierCount> distribution() {
        Map<String, Long> counts = memberTiers.distribution();
        List<TierCount> out = new ArrayList<>();
        for (Tier t : tiers.findAllByRank()) {
            out.add(new TierCount(t.code(), t.name(), t.rank(), t.thresholdSts(), t.multiplier(),
                    counts.getOrDefault(t.code(), 0L)));
        }
        return out;
    }

    public List<TierHistory> history(String memberId) {
        return history.findByMember(memberId);
    }

    @Transactional
    public Tier update(String code, TierUpdate req) {
        Tier existing = tiers.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Livello non trovato: " + code));

        long threshold = req.thresholdSts() != null ? req.thresholdSts() : existing.thresholdSts();
        if ("BASE".equals(code) && threshold != 0) {
            throw LhException.validation("TIER_THRESHOLDS_NOT_MONOTONIC", "Il livello BASE ha soglia fissa a 0.");
        }
        BigDecimal multiplier = req.multiplier() != null ? req.multiplier() : existing.multiplier();
        if (multiplier.signum() <= 0) {
            throw LhException.validation("TIER_MULTIPLIER_INVALID", "Il moltiplicatore deve essere positivo.");
        }
        List<String> benefits = req.benefits() != null ? req.benefits() : existing.benefits();
        Tier updated = new Tier(existing.code(),
                req.name() != null ? req.name() : existing.name(),
                existing.rank(), threshold, multiplier, benefits,
                req.color() != null ? req.color() : existing.color(),
                req.icon() != null ? req.icon() : existing.icon());

        assertMonotonic(updated);

        String benefitsJson = benefitsJson(benefits);
        tiers.upsert(updated, benefitsJson);
        audit.record("tier", code, AuditEntry.Action.UPDATE, "Livello " + code + " aggiornato",
                Map.of("thresholdSts", existing.thresholdSts(), "multiplier", existing.multiplier()),
                Map.of("thresholdSts", threshold, "multiplier", multiplier));
        return updated;
    }

    /** Le soglie devono crescere strettamente col rank (docs §3): BASE=0 < SILVER < GOLD < PLATINUM. */
    private void assertMonotonic(Tier changed) {
        List<Tier> scale = new ArrayList<>(tiers.findAllByRank());
        scale.replaceAll(t -> t.code().equals(changed.code()) ? changed : t);
        scale.sort(java.util.Comparator.comparingInt(Tier::rank));
        for (int i = 1; i < scale.size(); i++) {
            if (scale.get(i).thresholdSts() <= scale.get(i - 1).thresholdSts()) {
                throw LhException.validation("TIER_THRESHOLDS_NOT_MONOTONIC",
                        "Le soglie devono crescere col livello: " + scale.get(i - 1).code()
                                + " (" + scale.get(i - 1).thresholdSts() + ") ≥ " + scale.get(i).code()
                                + " (" + scale.get(i).thresholdSts() + ").");
            }
        }
    }

    private static String benefitsJson(List<String> benefits) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < benefits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(benefits.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
