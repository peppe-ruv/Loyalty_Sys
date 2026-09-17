package it.iren.loyalty.ingressadapters.publish;

import io.cloudevents.CloudEvent;
import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Pubblica l'evento canonico sul topic delle azioni; gli scarti vanno sulla DLQ con il motivo (RF-45). */
@Component
public class ActionPublisher {
    private final KafkaTemplate<String, byte[]> kafka;

    public ActionPublisher(KafkaTemplate<String, byte[]> kafka) {
        this.kafka = kafka;
    }

    public void publish(CloudEvent event) {
        // chiave = membro: tutti gli eventi di un membro finiscono nella stessa partizione (ordine garantito)
        kafka.send(EventTypes.TOPIC_ACTIONS, CanonicalEvents.memberId(event), CanonicalEvents.serialize(event));
    }

    public void reject(String source, String memberId, RewardingAction action, String reason) {
        var event = CanonicalEvents.of(EventTypes.ACTION_V1 + ".rejected", "urn:iren:loyalty:source:" + source,
                "member:" + memberId, Map.of("reason", reason, "action", action));
        kafka.send(EventTypes.TOPIC_DLQ, memberId, CanonicalEvents.serialize(event));
    }
}
