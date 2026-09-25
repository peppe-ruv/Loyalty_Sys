package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Effetto {@code plays.grant} (EVT-EFF-02; F-IW-05): credito di giocate su un concorso, idempotente su {@code effectId},
 * con fatto {@code contest.plays.granted}. Concorso sconosciuto → DLQ non ritentabile. Il credito resta anche se il
 * concorso non è ancora LIVE: si userà quando lo sarà.
 */
@Component
public class PlaysGrantHandler implements EventHandler {

    private final ContestRepository contests;
    private final PlayRepository plays;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public PlaysGrantHandler(ContestRepository contests, PlayRepository plays, LhEventFactory events, OutboxWriter outbox,
                             Clock clock) {
        this.contests = contests;
        this.plays = plays;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Effect.PLAYS_GRANT);
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        JsonNode d = event.data();
        String memberId = event.memberId();
        if (d == null || memberId == null) {
            throw new NonRetryableEventException("INVALID_EFFECT", "plays.grant senza membro o dati: " + event.id());
        }
        String code = d.path("contestCode").asString("");
        Contest c = contests.find(code).filter(x -> x.code().equals(code))
                .orElseThrow(() -> new NonRetryableEventException("CONTEST_NOT_FOUND", "Concorso sconosciuto: " + code));
        // count assente → 1 credito (come Q-230). Q-297 DECISA: count presente ma non intero o < 1 → DLQ INVALID_EFFECT,
        // nessun credito (prima valeva 1).
        JsonNode rawCount = d.get("count");
        int count = 1;
        if (rawCount != null && !rawCount.isNull()) {
            if (!rawCount.isIntegralNumber() || !rawCount.canConvertToInt() || rawCount.asInt() < 1) {
                throw new NonRetryableEventException("INVALID_EFFECT", "plays.grant con count non valido (" + rawCount
                        + "): serve un intero ≥ 1: " + event.id());
            }
            count = rawCount.asInt();
        }
        String effectId = d.path("effectId").asString(event.id());
        String campaign = d.hasNonNull("campaignCode") ? d.get("campaignCode").asString() : null;
        if (!plays.insertGrant(Ulid.next(clock), memberId, c.id(), count, effectId, campaign, clock.instant())) {
            return; // credito già registrato per questo effetto
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contestCode", c.code());
        data.put("count", count);
        data.put("effectId", effectId);
        if (campaign != null) {
            data.put("campaignCode", campaign);
        }
        outbox.write(events.childOf(event, LhEventTypes.Fact.CONTEST_PLAYS_GRANTED, data));
    }
}
