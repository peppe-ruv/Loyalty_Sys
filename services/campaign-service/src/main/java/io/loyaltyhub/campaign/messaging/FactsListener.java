package io.loyaltyhub.campaign.messaging;

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

/**
 * Consuma {@code lh.facts.v1} → snapshot membro e totali (docs/servizi/campaign-service.md §4). I fatti
 * {@code campaign.*} prodotti dal servizio stesso non hanno handler: vengono ignorati (nessun loop).
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class FactsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;

    public FactsListener(@Qualifier("campaignEventRouter") EventRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
    }

    @KafkaListener(
            groupId = "lh-campaign",
            topics = "${loyaltyhub.topics.facts:lh.facts.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onFact(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        router.route(event);
        ack.acknowledge();
    }
}
