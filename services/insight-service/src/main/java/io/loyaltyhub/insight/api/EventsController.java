package io.loyaltyhub.insight.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
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
 * Lettura dell'event store (docs/servizi/insight-service.md §3): elenco filtrato e paginato ({@code ?page&size},
 * risposta {@code {items, page}}, docs/06 §2) e dettaglio con payload.
 */
@RestController
@RequestMapping("/v1/events")
public class EventsController {

    private static final int DEFAULT_SIZE = 100;

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

    @GetMapping
    public PageResponse<EventSummary> list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        Paging p = Paging.of(page, size, limit, DEFAULT_SIZE);
        Instant fromI = Paging.instant(from);
        Instant toI = Paging.instant(to);
        List<EventSummary> items = events.search(topic, family, type, memberId, correlationId, source,
                        fromI, toI, q, p.size(), p.offset()).stream()
                .map(EventsController::summary)
                .toList();
        long total = events.count(topic, family, type, memberId, correlationId, source, fromI, toI, q);
        return PageResponse.of(items, p.page(), p.size(), total);
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
}
