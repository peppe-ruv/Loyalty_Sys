package io.loyaltyhub.wallet.messaging;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.inbox.EventRouter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** Consuma {@code lh.effects.v1} → applicazione degli effetti punti (docs/06 §5). */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class EffectsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public EffectsListener(@Qualifier("walletEventRouter") EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            groupId = "lh-wallet",
            topics = "${loyaltyhub.topics.effects:lh.effects.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onEffect(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        router.route(event);
        ack.acknowledge();
    }
}
