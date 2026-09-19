package io.loyaltyhub.insight.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Registra nell'event store gli eventi osservati sui topic (docs/servizi/insight-service.md §5).
 * Idempotente su {@code event_id}: un duplicato non crea una seconda riga né gonfia le statistiche.
 */
@Service
public class EventIngestService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestService.class);
    private static final TypeReference<LhEvent<JsonNode>> EVENT_TYPE = new TypeReference<>() {
    };

    private final EventStoreRepository events;
    private final TopicStatRepository topicStats;
    private final ObjectMapper mapper;

    public EventIngestService(EventStoreRepository events, TopicStatRepository topicStats, ObjectMapper mapper) {
        this.events = events;
        this.topicStats = topicStats;
        this.mapper = mapper;
    }

    @Transactional
    public void ingest(String topic, String family, ConsumerRecord<String, String> record) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        String type = event.type() == null ? "" : event.type();
        StoredEvent stored = new StoredEvent(
                event.id(), topic, family, type, shortType(type), event.source(), event.memberId(),
                event.lhcorrelationid(), event.lhcausationid(), event.lhhop(), event.lhactor(),
                header(record, LhHeaders.ERROR_CODE), event.time(), null,
                record.partition(), record.offset(), record.value());

        boolean isNew = events.insert(stored);
        if (isNew) {
            topicStats.record(topic, event.time(), record.partition(), record.offset());
        } else {
            log.debug("Evento già in store (duplicato), ignorato: {} su {}", event.id(), topic);
        }
    }

    private static String shortType(String type) {
        LhFamily f = LhFamily.of(type);
        if (f != null && type.startsWith(f.typePrefix())) {
            return type.substring(f.typePrefix().length());
        }
        return type;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
