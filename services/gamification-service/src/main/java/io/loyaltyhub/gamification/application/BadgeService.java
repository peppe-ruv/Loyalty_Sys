package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assegnazione dei badge (F-ACH-03): al completamento di un obiettivo ({@code ACHIEVEMENT}) o da effetto
 * {@code badge.award} ({@code CAMPAIGN}). Un badge si ottiene una volta; il fatto {@code badge.awarded} rientra come
 * azione dal ponte (docs/05 §6) e può essere premiato dalle campagne ({@code CMP-BADGE-BONUS}).
 */
@Service
public class BadgeService {

    private final BadgeRepository badges;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public BadgeService(BadgeRepository badges, LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.badges = badges;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Assegna e, se nuovo, scrive {@code badge.awarded} come figlio di {@code cause}. */
    public boolean award(String memberId, BadgeRepository.Badge badge, String origin, String effectId, LhEvent<?> cause) {
        if (!badges.award(memberId, badge.code(), origin, effectId, clock.instant())) {
            return false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("badgeCode", badge.code());
        data.put("badgeName", badge.name());
        data.put("origin", origin);
        outbox.write(events.childOf(cause, LhEventTypes.Fact.BADGE_AWARDED, data));
        return true;
    }
}
