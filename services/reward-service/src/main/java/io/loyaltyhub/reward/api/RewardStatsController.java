package io.loyaltyhub.reward.api;

import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /v1/rewards/stats} (docs/servizi/reward-service.md §3; BO-10, BO-01 riga 4): premi per stato, richieste
 * per stato, premi più richiesti e premi con stock sotto il 10 % (esauriti compresi).
 */
@RestController
public class RewardStatsController {

    public record LowStock(String id, String code, String name, int stockRemaining, int stockTotal) {
    }

    public record RewardStats(Map<String, Long> rewardsByStatus, Map<String, Long> redemptionsByStatus,
                              List<RedemptionRepository.TopReward> topRewards, List<LowStock> lowStock) {
    }

    private final RewardRepository rewards;
    private final RedemptionRepository redemptions;

    public RewardStatsController(RewardRepository rewards, RedemptionRepository redemptions) {
        this.rewards = rewards;
        this.redemptions = redemptions;
    }

    @GetMapping("/v1/rewards/stats")
    @Transactional(readOnly = true)
    public RewardStats stats() {
        List<Reward> all = rewards.findAll();
        Map<String, Long> byStatus = new TreeMap<>();
        for (Reward r : all) {
            byStatus.merge(r.status().name(), 1L, Long::sum);
        }
        List<LowStock> low = all.stream()
                .filter(r -> !r.unlimited() && r.stockTotal() > 0 && r.stockRemaining() * 10 < r.stockTotal())
                .map(r -> new LowStock(r.id(), r.code(), r.name(), r.stockRemaining(), r.stockTotal()))
                .toList();
        return new RewardStats(byStatus, redemptions.countByStatus(), redemptions.topRewards(5), low);
    }
}
