package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.gamification.application.LeaderboardService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** {@code wallet.points.earned} → classifiche a punti (docs/servizi/gamification-service.md §4). */
@Component
public class PointsEarnedHandler implements EventHandler {

    private final LeaderboardService leaderboards;

    public PointsEarnedHandler(LeaderboardService leaderboards) {
        this.leaderboards = leaderboards;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Fact.WALLET_POINTS_EARNED);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        leaderboards.onPointsEarned(event);
    }
}
