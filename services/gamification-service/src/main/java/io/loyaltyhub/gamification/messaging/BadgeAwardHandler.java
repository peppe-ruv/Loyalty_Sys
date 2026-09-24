package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.gamification.application.BadgeService;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Effetto {@code badge.award} (EVT-EFF-04): badge da campagna, idempotente; badge sconosciuto → DLQ non ritentabile. */
@Component
public class BadgeAwardHandler implements EventHandler {

    private final BadgeRepository badges;
    private final BadgeService badgeService;

    public BadgeAwardHandler(BadgeRepository badges, BadgeService badgeService) {
        this.badges = badges;
        this.badgeService = badgeService;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Effect.BADGE_AWARD);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        JsonNode d = event.data();
        String memberId = event.memberId();
        if (d == null || memberId == null) {
            throw new NonRetryableEventException("INVALID_EFFECT", "badge.award senza membro o dati: " + event.id());
        }
        String code = d.path("badgeCode").asString("");
        BadgeRepository.Badge badge = badges.find(code)
                .orElseThrow(() -> new NonRetryableEventException("BADGE_NOT_FOUND", "Badge sconosciuto: " + code));
        badgeService.award(memberId, badge, "CAMPAIGN", d.path("effectId").asString(event.id()), event);
    }
}
