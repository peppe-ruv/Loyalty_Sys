package io.loyaltyhub.insight.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.domain.StoredEvent;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Lettura dell'event store (docs/servizi/insight-service.md §3): elenco filtrato e dettaglio con payload.
 * Tracciati, KPI, audit, DLQ e SSE arrivano nelle fette successive di M2.
 */
@RestController
@RequestMapping("/v1/events")
public class EventsController {

    private static final int MAX_LIMIT = 200;

    private final EventStoreRepository events;
    private final ObjectMapper mapper;

    public EventsController(EventStoreRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    public record EventSummary(String eventId, String topic, String family, String shortType, String source,
                               String memberId, String correlationId, Integer hop, String actor, String errorCode,
                               Instant eventTime, Instant receivedAt) {
    }

    public record EventDetail(String eventId, String topic, String family, String type, String shortType,
                              String source, String memberId, String correlationId, String causationId,
                              Integer hop, String actor, String errorCode, Instant eventTime, Instant receivedAt,
                              int partition, long offset, JsonNode payload) {
    }

    public record EventsPage(List<EventSummary> items, int count) {
    }

    @GetMapping
    public EventsPage list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<EventSummary> items = events.search(topic, family, type, memberId, correlationId, source,
                        parseInstant(from), parseInstant(to), q, capped).stream()
                .map(EventsController::summary)
                .toList();
        return new EventsPage(items, items.size());
    }

    @GetMapping("/{eventId}")
    public EventDetail byId(@PathVariable String eventId) {
        StoredEvent e = events.findById(eventId)
                .orElseThrow(() -> LhException.notFound("Evento non trovato: " + eventId));
        return new EventDetail(e.eventId(), e.topic(), e.family(), e.type(), e.shortType(), e.source(),
                e.memberId(), e.correlationId(), e.causationId(), e.hop(), e.actor(), e.errorCode(),
                e.eventTime(), e.receivedAt(), e.partition(), e.offset(), payload(e.payloadJson()));
    }

    private static EventSummary summary(StoredEvent e) {
        return new EventSummary(e.eventId(), e.topic(), e.family(), e.shortType(), e.source(), e.memberId(),
                e.correlationId(), e.hop(), e.actor(), e.errorCode(), e.eventTime(), e.receivedAt());
    }

    private JsonNode payload(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw LhException.badRequest("Istante non valido (atteso ISO-8601): " + value);
        }
    }
}
