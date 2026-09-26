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

/** Consuma {@code lh.actions.v1} → motore (docs/06 §5): deserializza, instrada, ack dopo il commit. */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class ActionsListener {

    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventRouter router;
    private final ObjectMapper mapper;
    private final MemberSnapshotAwait snapshotAwait;

    public ActionsListener(@Qualifier("campaignEventRouter") EventRouter router, ObjectMapper mapper,
                           MemberSnapshotAwait snapshotAwait) {
        this.router = router;
        this.mapper = mapper;
        this.snapshotAwait = snapshotAwait;
    }

    @KafkaListener(
            groupId = "lh-campaign",
            topics = "${loyaltyhub.topics.actions:lh.actions.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onAction(ConsumerRecord<String, String> record, Acknowledgment ack) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        snapshotAwait.await(event.memberId()); // campaign §5: il fatto member.registered può essere in arrivo
        router.route(event);
        ack.acknowledge();
    }
}
