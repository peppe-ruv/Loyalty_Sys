package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.gamification.application.AchievementService;
import io.loyaltyhub.gamification.application.LeaderboardService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Tutte le azioni ({@code lh.actions.v1}, famiglia {@code action.*}): avanzano gli obiettivi che le osservano e le
 * classifiche a conteggio che le elencano. Un solo handler per famiglia; la ricezione resta idempotente.
 */
@Component
public class GameActionHandler implements EventHandler {

    private final AchievementService achievements;
    private final LeaderboardService leaderboards;

    public GameActionHandler(AchievementService achievements, LeaderboardService leaderboards) {
        this.achievements = achievements;
        this.leaderboards = leaderboards;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Action.PREFIX + "*");
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        achievements.onAction(event);
        leaderboards.onAction(event);
    }
}
