package io.loyaltyhub.fraudservice.messaging;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.fraudservice.app.RiskService;
import io.loyaltyhub.fraudservice.app.RiskStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Osserva i topic di dominio e alimenta lo store dei segnali (RF-131): azioni (transazioni, resi, check-in con
 * posizione, codici respinti, adesioni, dispositivi), movimenti (accumuli), riscatti confermati, giocate.
 * Dopo ogni osservazione rilevante rivaluta il membro. Idempotente per id evento.
 */
@Component
public class SignalConsumer {
    private static final Set<String> SERVICE_ACTIONS = Set.of(EventTypes.ACTION_EXPERIMENT_EXPOSED, EventTypes.ACTION_CAMPAIGN_COMPLETED, EventTypes.ACTION_CAMPAIGN_ENTERED,
            EventTypes.ACTION_CHURN_RISK_CHANGED, EventTypes.ACTION_OFFER_PRESENTED, EventTypes.ACTION_MESSAGE_SENT, EventTypes.ACTION_FEEDBACK_REQUESTED, EventTypes.ACTION_SEGMENT_ENTERED, EventTypes.ACTION_TIER_CHANGED);
    public static final String ACTION_CODE_REJECTED = "CODE_REJECTED";

    private final RiskStore store;
    private final RiskService risk;

    public SignalConsumer(RiskStore store, RiskService risk) { this.store = store; this.risk = risk; }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, groupId = "fraud-service", concurrency = "${fraud.consumer.concurrency:4}")
    public void onAction(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(e.getType())) return;
        var a = CanonicalEvents.data(e, RewardingAction.class);
        if (a.isReversal() || SERVICE_ACTIONS.contains(a.actionType())) return;
        String memberId = CanonicalEvents.memberId(e);
        Map<String, Object> attrs = a.attributes() == null ? Map.of() : a.attributes();
        String device = str(attrs.get("deviceId"));
        Double lat = num(attrs.get(EventTypes.ATTR_LAT)), lon = num(attrs.get(EventTypes.ATTR_LON));
        String kind = switch (a.actionType()) {
            case EventTypes.ACTION_MEMBER_ENROLLED -> "ENROLLED";
            case EventTypes.ACTION_TRANSACTION_RETURNED -> "RETURN";
            case EventTypes.ACTION_CHECK_IN -> "CHECK_IN";
            case ACTION_CODE_REJECTED -> "CODE_FAILED";
            case EventTypes.ACTION_CODE_REDEEMED -> Boolean.FALSE.equals(attrs.get("valid")) ? "CODE_FAILED" : "ACTION";
            case EventTypes.ACTION_CONTEST_PLAYED -> "PLAY";
            default -> "ACTION";
        };
        if (store.observe(e.getId(), memberId, kind, a.actionType(), 0, device, lat, lon, a.occurredAt() == null ? Instant.now() : a.occurredAt()))
            risk.assess(memberId, e.getId());
    }

    @KafkaListener(topics = EventTypes.TOPIC_MOVEMENTS, groupId = "fraud-service")
    @SuppressWarnings("unchecked")
    public void onMovement(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        long amount = m.get("amount") instanceof Number n ? n.longValue() : 0;
        if (!"EARN".equals(m.get("kind")) || amount <= 0) return;
        String memberId = CanonicalEvents.memberId(e);
        if (store.observe(e.getId(), memberId, "EARNING", str(m.get("currency")), amount, null, null, null, Instant.now()))
            risk.assess(memberId, e.getId());
    }

    @KafkaListener(topics = EventTypes.TOPIC_REDEMPTIONS, groupId = "fraud-service")
    @SuppressWarnings("unchecked")
    public void onRedemption(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        if (!"CONFIRMED".equals(m.get("status"))) return;
        String memberId = CanonicalEvents.memberId(e);
        if (store.observe(e.getId(), memberId, "REDEMPTION", str(m.get("rewardType")), m.get("points") instanceof Number n ? n.longValue() : 0, null, null, null, Instant.now()))
            risk.assess(memberId, e.getId());
    }

    @KafkaListener(topics = EventTypes.TOPIC_CONTESTS, groupId = "fraud-service")
    public void onContest(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        String memberId = CanonicalEvents.memberId(e);
        if (memberId != null && store.observe(e.getId(), memberId, "PLAY", null, 0, null, null, null, Instant.now())) risk.assess(memberId, e.getId());
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static Double num(Object o) { return o instanceof Number n ? n.doubleValue() : o instanceof String s && !s.isBlank() ? Double.valueOf(s) : null; }
}
