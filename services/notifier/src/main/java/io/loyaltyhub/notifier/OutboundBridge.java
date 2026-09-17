package io.loyaltyhub.notifier;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.notifier.templates.MessageTemplate;
import io.loyaltyhub.notifier.templates.TemplateSource;
import io.loyaltyhub.notifier.webhooks.WebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ponte verso l'esterno (RI-06, RF-77, RF-78): per ogni evento di dominio (1) inoltra ai webhook sottoscritti,
 * (2) se esiste un modello attivo per evento e canale, compone il messaggio e lo passa al {@link MessageSender}
 * (email/SMS/push tramite i fornitori aziendali; in locale: log). I connettori CRM/data platform via CDC restano adattatori per destinazione.
 */
@Component
public class OutboundBridge {
    private static final Logger log = LoggerFactory.getLogger(OutboundBridge.class);
    private final WebhookDispatcher webhooks;
    private final TemplateSource templates;
    private final MessageSender sender;

    public OutboundBridge(WebhookDispatcher webhooks, TemplateSource templates, MessageSender sender) {
        this.webhooks = webhooks; this.templates = templates; this.sender = sender;
    }

    @KafkaListener(topics = {EventTypes.TOPIC_MOVEMENTS, EventTypes.TOPIC_TIERS, EventTypes.TOPIC_REDEMPTIONS, EventTypes.TOPIC_CONTESTS, EventTypes.TOPIC_MEMBERS, EventTypes.TOPIC_SEGMENTS})
    public void forward(byte[] payload) {
        var event = CanonicalEvents.deserialize(payload);
        String memberId = CanonicalEvents.memberId(event);
        webhooks.dispatch(event.getType(), event.getId(), payload);
        @SuppressWarnings("unchecked") Map<String, Object> data = CanonicalEvents.data(event, Map.class);
        for (MessageTemplate.Channel ch : MessageTemplate.Channel.values()) {
            templates.find(event.getType(), ch, "it").ifPresent(t -> sender.send(memberId, t.render(Map.of("event", data, "memberId", memberId))));
        }
        log.debug("outbound event {} for member {}", event.getType(), memberId);
    }
}
