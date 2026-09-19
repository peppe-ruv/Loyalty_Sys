package io.loyaltyhub.member.messaging;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.inbox.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consuma {@code lh.facts.v1} e instrada all'{@link EventRouter} (docs/06 §5): {@code wallet.points.*} e
 * {@code tier.*} aggiornano {@code member_projection}. I fatti prodotti da member stesso sono ignorati.
 */
@Component
public class FactsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public FactsListener(EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${loyaltyhub.topics.facts:lh.facts.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onFact(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        router.route(event);
        ack.acknowledge();
    }
}
