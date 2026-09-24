package io.loyaltyhub.insight.messaging;

import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.insight.application.EventIngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * insight consuma <strong>tutti e 5</strong> i topic col gruppo {@code lh-insight} (docs/servizi/insight-service.md
 * §4): ogni evento finisce nell'event store con la famiglia del topic. Nessuna logica di dominio, solo copia
 * idempotente; ack manuale dopo la scrittura (un errore non fa ack → l'error handler di lh-common ritenta).
 */
@Component
public class TopicListeners {

    private static final Logger log = LoggerFactory.getLogger(TopicListeners.class);

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
        // Voce DLQ per BO-27 (M7.3). Se è un record DLQ nato da un fallimento di questo stesso listener
        // (lh-original-topic = lh.dlq.v1), non lo si rilancia: rientrerebbe in DLQ all'infinito.
        try {
            ingest.ingestDlq(record);
        } catch (RuntimeException e) {
            Header origin = record.headers().lastHeader(LhHeaders.ORIGINAL_TOPIC);
            if (origin != null && record.topic().equals(new String(origin.value(), StandardCharsets.UTF_8))) {
                log.error("Record DLQ non registrabile (ciclo evitato), scartato: {}", e.toString());
            } else {
                throw e;
            }
        }
        ack.acknowledge();
    }
}
