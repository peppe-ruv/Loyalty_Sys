package io.loyaltyhub.gamification.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.inbox.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Consuma {@code lh.effects.v1} per gamification (docs/servizi/gamification-service.md §4); i tipi senza handler si ignorano. */
@Component
public class EffectsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public EffectsListener(@Qualifier("gamificationEventRouter") EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            groupId = "lh-gamification",
            topics = "${loyaltyhub.topics.effects:lh.effects.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onEffect(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        router.route(event);
        ack.acknowledge();
    }
}
