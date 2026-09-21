package io.loyaltyhub.insight.messaging;

import io.loyaltyhub.insight.application.EventIngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * insight consuma <strong>tutti e 5</strong> i topic col gruppo {@code lh-insight} (docs/servizi/insight-service.md
 * §4): ogni evento finisce nell'event store con la famiglia del topic. Nessuna logica di dominio, solo copia
 * idempotente; ack manuale dopo la scrittura (un errore non fa ack → l'error handler di lh-common ritenta).
 */
@Component
public class TopicListeners {

    private final EventIngestService ingest;

    public TopicListeners(EventIngestService ingest) {
        this.ingest = ingest;
    }

    @KafkaListener(groupId = "lh-insight", topics = "${loyaltyhub.topics.actions:lh.actions.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onAction(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ingest.ingest(record.topic(), "ACTION", record);
        ack.acknowledge();
    }

    @KafkaListener(groupId = "lh-insight", topics = "${loyaltyhub.topics.effects:lh.effects.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onEffect(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ingest.ingest(record.topic(), "EFFECT", record);
        ack.acknowledge();
    }

    @KafkaListener(groupId = "lh-insight", topics = "${loyaltyhub.topics.facts:lh.facts.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onFact(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ingest.ingest(record.topic(), "FACT", record);
        ack.acknowledge();
    }

    @KafkaListener(groupId = "lh-insight", topics = "${loyaltyhub.topics.audit:lh.audit.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onAudit(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ingest.ingest(record.topic(), "AUDIT", record);
        ack.acknowledge();
    }

    @KafkaListener(groupId = "lh-insight", topics = "${loyaltyhub.topics.dlq:lh.dlq.v1}",
            containerFactory = "lhKafkaListenerContainerFactory")
    public void onDlq(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ingest.ingest(record.topic(), "DLQ", record);
        ack.acknowledge();
    }
}
