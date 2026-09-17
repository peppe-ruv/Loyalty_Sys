package io.loyaltyhub.readmodel.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.readmodel.domain.ContextProjector;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Consumer dei topic di dominio: ogni evento aggiorna il Customer 360 (RF-125). Chiave = membro, quindi ordine garantito per membro. */
@Component
public class Projections {
    private final ContextStore store;
    public Projections(ContextStore store) { this.store = store; }

    @KafkaListener(topics = EventTypes.TOPIC_ACTIONS, groupId = "read-model-actions", concurrency = "${readmodel.concurrency:4}")
    public void onAction(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        if (!EventTypes.ACTION_V1.equals(e.getType())) return;
        var a = CanonicalEvents.data(e, RewardingAction.class);
        if (a.isReversal()) return;
        Map<String, Object> attrs = a.attributes() == null ? Map.of() : a.attributes();
        Double amount = attrs.get("amountEur") == null ? null : Double.valueOf(attrs.get("amountEur").toString());
        String channel = attrs.get("channel") == null ? null : attrs.get("channel").toString();
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onAction(c, a.actionType(), a.occurredAt() == null ? Instant.now() : a.occurredAt(), channel, amount, a.externalRef(), attrs));
    }

    @KafkaListener(topics = EventTypes.TOPIC_MOVEMENTS, groupId = "read-model-movements")
    public void onMovement(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        String wallet = String.valueOf(m.get("currency"));
        long balance = num(m.get("balance")), pending = num(m.get("pending")), blocked = num(m.get("blocked")), amount = num(m.get("amount"));
        String kind = String.valueOf(m.get("kind"));
        store.update(CanonicalEvents.memberId(e), c -> {
            var w = c.loyalty().wallets().getOrDefault(wallet, new io.loyaltyhub.readmodel.domain.CustomerContext.Wallet(0, 0, 0, 0, 0, 0));
            long earned = w.earned() + ("EARN".equals(kind) || "TRANSFER_IN".equals(kind) ? Math.max(0, amount) : 0);
            long spent = w.spent() + ("SPEND".equals(kind) || "TRANSFER_OUT".equals(kind) ? Math.abs(Math.min(0, amount)) : 0);
            long expired = w.expired() + ("EXPIRY".equals(kind) ? Math.abs(amount) : 0);
            var next = ContextProjector.onWallet(c, wallet, balance, earned, spent, pending, blocked, expired);
            if ("STATUS".equals(wallet) && amount > 0) next = new io.loyaltyhub.readmodel.domain.CustomerContext(next.memberId(), next.identity(),
                    new io.loyaltyhub.readmodel.domain.CustomerContext.Loyalty(next.loyalty().wallets(), next.loyalty().tier(), next.loyalty().statusPointsYear() + amount, next.loyalty().tierSince(), next.loyalty().segments()),
                    next.behaviour(), next.engagement(), next.risk(), next.consents(), next.predictions(), next.updatedAt());
            return next;
        });
    }

    @KafkaListener(topics = EventTypes.TOPIC_TIERS, groupId = "read-model-tiers")
    public void onTier(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onTier(c, String.valueOf(m.get("toTier")), Instant.now()));
    }

    @KafkaListener(topics = EventTypes.TOPIC_SEGMENTS, groupId = "read-model-segments")
    public void onSegment(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onSegment(c, String.valueOf(m.get("segmentId")), Boolean.TRUE.equals(m.get("entered"))));
    }

    @KafkaListener(topics = EventTypes.TOPIC_MEMBERS, groupId = "read-model-members")
    @SuppressWarnings("unchecked")
    public void onMember(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onMember(c, String.valueOf(m.get("status")),
                m.get("enrolledAt") == null ? null : Instant.parse(m.get("enrolledAt").toString()), str(m.get("enrollmentChannel")), str(m.get("referredBy")),
                (Map<String, String>) m.get("labels"), (Map<String, Boolean>) m.get("consents")));
    }

    @KafkaListener(topics = EventTypes.TOPIC_REDEMPTIONS, groupId = "read-model-redemptions")
    public void onRedemption(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onRedemption(c, String.valueOf(m.get("status"))));
    }

    @KafkaListener(topics = EventTypes.TOPIC_DECISIONS, groupId = "read-model-decisions")
    public void onDecision(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> {
            if (m.get("predictions") instanceof Map<?, ?> p) {
                var preds = new java.util.HashMap<String, Double>();
                p.forEach((k, v) -> { if (v instanceof Number n) preds.put(k.toString(), n.doubleValue()); });
                c = ContextProjector.onPredictions(c, preds);
            }
            if ("NO_ACTION".equals(m.get("action"))) return c;
            return ContextProjector.onDecision(c, str(m.get("decisionId")), str(m.get("action")), str(m.get("reference")), str(m.get("channel")), Instant.now());
        });
    }

    @KafkaListener(topics = EventTypes.TOPIC_DELIVERIES, groupId = "read-model-deliveries")
    public void onDelivery(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        @SuppressWarnings("unchecked") Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        if (!"SENT".equals(m.get("status")) && !"PRESENTED".equals(m.get("status"))) return;
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onDelivery(c, str(m.get("channel")), Instant.now()));
    }

    @KafkaListener(topics = EventTypes.TOPIC_RISK, groupId = "read-model-risk")
    @SuppressWarnings("unchecked")
    public void onRisk(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> ContextProjector.onRisk(c, (int) num(m.get("score")), str(m.get("level")), (List<String>) m.getOrDefault("reasonCodes", List.of()), Instant.now()));
    }

    @KafkaListener(topics = EventTypes.TOPIC_CONSENTS, groupId = "read-model-consents")
    @SuppressWarnings("unchecked")
    public void onConsent(byte[] payload) {
        var e = CanonicalEvents.deserialize(payload);
        Map<String, Object> m = CanonicalEvents.data(e, Map.class);
        store.update(CanonicalEvents.memberId(e), c -> {
            var consents = new java.util.HashMap<>(c.consents());
            consents.put(str(m.get("purpose")), Boolean.TRUE.equals(m.get("granted")));
            return ContextProjector.onMember(c, c.identity().status(), c.identity().enrolledAt(), c.identity().channel(), c.identity().referredBy(), c.identity().labels(), consents);
        });
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
