package io.loyaltyhub.insight.application;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhFamily;
import io.loyaltyhub.common.event.LhHeaders;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.insight.domain.AuditRecord;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.AuditRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.MetricRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import io.loyaltyhub.insight.live.EventSummaries;
import io.loyaltyhub.insight.live.LiveEvent;
import io.loyaltyhub.insight.live.LiveEventHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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
    private final MetricRepository metrics;
    private final AuditRepository audits;
    private final LiveEventHub liveHub;
    private final ObjectMapper mapper;

    public EventIngestService(EventStoreRepository events, TopicStatRepository topicStats,
                              MetricRepository metrics, AuditRepository audits, LiveEventHub liveHub,
                              ObjectMapper mapper) {
        this.events = events;
        this.topicStats = topicStats;
        this.metrics = metrics;
        this.audits = audits;
        this.liveHub = liveHub;
        this.mapper = mapper;
    }

    @Transactional
    public void ingest(String topic, String family, ConsumerRecord<String, String> record) {
        LhEvent<JsonNode> event = mapper.readValue(record.value(), EVENT_TYPE);
        String type = event.type() == null ? "" : event.type();
        String shortType = shortType(type);
        StoredEvent stored = new StoredEvent(
                event.id(), topic, family, type, shortType, event.source(), event.memberId(),
                event.lhcorrelationid(), event.lhcausationid(), event.lhhop(), event.lhactor(),
                header(record, LhHeaders.ERROR_CODE), event.time(), null,
                record.partition(), record.offset(), record.value());

        boolean isNew = events.insert(stored);
        if (isNew) {
            topicStats.record(topic, event.time(), record.partition(), record.offset());
            updateMetrics(family, shortType, event, stored);
            if ("AUDIT".equals(family)) {
                recordAudit(event);
            }
            liveHub.publish(new LiveEvent(event.id(), topic, family, shortType, event.memberId(),
                    event.lhcorrelationid(), event.time(), EventSummaries.of(shortType, event.data())));
        } else {
            log.debug("Evento già in store (duplicato), ignorato: {} su {}", event.id(), topic);
        }
    }

    /** Aggiorna gli aggregati giornalieri (docs §5). Solo le metriche con eventi in M1/M2. */
    private void updateMetrics(String family, String shortType, LhEvent<JsonNode> event, StoredEvent stored) {
        LocalDate day = (event.time() != null ? event.time() : Instant.now()).atZone(ZoneOffset.UTC).toLocalDate();
        JsonNode data = event.data() == null ? mapper.createObjectNode() : event.data();
        switch (family) {
            case "ACTION" -> {
                metrics.increment(day, "actions", MetricRepository.TOTAL, MetricRepository.TOTAL, 1);
                metrics.increment(day, "actions", "source", sourceCode(stored.source()), 1);
            }
            case "DLQ" -> metrics.increment(day, "dlq", MetricRepository.TOTAL, MetricRepository.TOTAL, 1);
            default -> {
                // fatti e effetti sotto
            }
        }
        switch (shortType) {
            case "wallet.points.earned" -> {
                long amount = data.path("amount").asLong(0);
                String currency = data.path("currency").asString("PTS");
                metrics.increment(day, "points_earned", MetricRepository.TOTAL, MetricRepository.TOTAL, amount);
                metrics.increment(day, "points_earned", "currency", currency, amount);
            }
            case "member.registered" ->
                    metrics.increment(day, "members_new", MetricRepository.TOTAL, MetricRepository.TOTAL, 1);
            case "tier.upgraded", "tier.changed" ->
                    metrics.increment(day, "tier_changes", MetricRepository.TOTAL, MetricRepository.TOTAL, 1);
            default -> {
                // altri tipi: nessuna metrica in M2
            }
        }
    }

    /** Estrae la voce di audit dall'evento {@code io.loyaltyhub.audit.entry} e la registra (docs/05 §6). */
    private void recordAudit(LhEvent<JsonNode> event) {
        JsonNode data = event.data();
        if (data == null || data.isNull()) {
            log.warn("Evento audit senza data, ignorato: {}", event.id());
            return;
        }
        String actor = event.lhactor() == null ? "" : event.lhactor();
        int colon = actor.indexOf(':');
        String role = colon > 0 ? actor.substring(0, colon) : (actor.isBlank() ? null : actor);
        String name = colon >= 0 && colon < actor.length() - 1 ? actor.substring(colon + 1) : null;
        audits.insert(new AuditRecord(
                Ulid.next(), event.id(), event.time(), role, name,
                data.path("service").asString(""), data.path("entityType").asString(""),
                data.path("entityId").asString(""), data.path("action").asString(""),
                data.path("summary").asString(""), nodeOrNull(data.get("before")), nodeOrNull(data.get("after")),
                event.lhcorrelationid()));
    }

    private static JsonNode nodeOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static String sourceCode(String source) {
        if (source == null) {
            return "unknown";
        }
        int i = source.lastIndexOf(':');
        return i >= 0 ? source.substring(i + 1) : source;
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
