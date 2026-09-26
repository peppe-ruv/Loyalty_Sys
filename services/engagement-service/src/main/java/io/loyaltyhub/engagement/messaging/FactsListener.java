package io.loyaltyhub.engagement.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consuma {@code lh.facts.v1} per engagement (docs/servizi/engagement-service.md §4): tutti i fatti del membro passano
 * dalle regole di notifica e aggiornano lo snapshot. I fatti prodotti da engagement stesso ({@code message.delivered})
 * sono ignorati qui, prima del router: niente regole, niente {@code processed_event}, nessun ciclo.
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class FactsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public FactsListener(@Qualifier("engagementEventRouter") EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            groupId = "lh-engagement",
            topics = "${loyaltyhub.topics.facts:lh.facts.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onFact(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        if (!LhEventTypes.Fact.MESSAGE_DELIVERED.equals(event.type())) {
            router.route(event);
        }
        ack.acknowledge();
    }
}
