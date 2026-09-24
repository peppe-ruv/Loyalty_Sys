package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.gamification.application.AchievementService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Tutte le azioni ({@code lh.actions.v1}, famiglia {@code action.*}) avanzano gli obiettivi che le osservano. */
@Component
public class AchievementActionHandler implements EventHandler {

    private final AchievementService achievements;

    public AchievementActionHandler(AchievementService achievements) {
        this.achievements = achievements;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Action.PREFIX + "*");
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        achievements.onAction(event);
    }
}
