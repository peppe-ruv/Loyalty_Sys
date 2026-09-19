package io.loyaltyhub.ingestion.messaging;

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
 * Listener di prova dell'archetipo (M0.5): consuma {@code lh.actions.v1} e instrada all'{@link EventRouter}.
 * Niente logica qui (docs/06 §5): deserializza, instrada, poi ack manuale dopo il commit.
 * Un'eccezione non fa ack: l'error handler di lh-common ritenta e poi porta in DLQ.
 */
@Component
public class ActionsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public ActionsListener(EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${loyaltyhub.topics.actions:lh.actions.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onAction(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        router.route(event);
        ack.acknowledge();
    }
}
