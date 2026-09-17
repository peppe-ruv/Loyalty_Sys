package it.iren.loyalty.contestservice;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Eventi di esito dei concorsi (D08, RF-38): CONTEST_RESULT per CRM/BI/notifiche e le azioni interne CONTEST_PLAYED /
 * CONTEST_WON sul topic delle azioni. Pubblicati dopo il commit della giocata; in produzione via outbox come il ledger.
 */
@Component
public class ContestEvents {
    private final KafkaTemplate<String, byte[]> kafka;
    public ContestEvents(KafkaTemplate<String, byte[]> kafka) { this.kafka = kafka; }

    public void played(String contestId, String memberId, String playId, boolean won, String prizeCode, Instant at) {
        Map<String, Object> data = new HashMap<>(Map.of("contestId", contestId, "playId", playId, "won", won, "kind", "INSTANT_WIN"));
        if (prizeCode != null) data.put("prizeCode", prizeCode);
        kafka.send(EventTypes.TOPIC_CONTESTS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.CONTEST_RESULT_V1, "urn:iren:loyalty:contests", "member:" + memberId, data)));
        action(memberId, EventTypes.ACTION_CONTEST_PLAYED, "contest:" + playId + ":PLAYED", contestId, at, data);
        if (won) action(memberId, EventTypes.ACTION_CONTEST_WON, "contest:" + playId + ":WON", contestId, at, data);
    }

    public void spun(String wheelId, String memberId, String spinId, boolean won, String slotId, Instant at) {
        Map<String, Object> data = Map.of("contestId", wheelId, "playId", spinId, "won", won, "kind", "WHEEL", "slotId", slotId);
        kafka.send(EventTypes.TOPIC_CONTESTS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.CONTEST_RESULT_V1, "urn:iren:loyalty:contests", "member:" + memberId, data)));
        action(memberId, "WHEEL_SPUN", "wheel:" + spinId + ":SPUN", wheelId, at, data);
    }

    private void action(String memberId, String type, String key, String ref, Instant at, Map<String, Object> attrs) {
        var a = new RewardingAction(type, key, ref, at, null, attrs);
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.ACTION_V1, "urn:iren:loyalty:contests", "member:" + memberId, a)));
    }
}
