package io.loyaltyhub.insight.api;

import io.loyaltyhub.insight.live.LiveEventHub;
import io.loyaltyhub.insight.live.LiveEventHub.Filter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stream live degli eventi (docs/servizi/insight-service.md §3, ADR-020): {@code GET /v1/stream/events}
 * ({@code text/event-stream}). Il browser vi si collega <strong>direttamente</strong> (le funzioni serverless
 * non reggono connessioni lunghe): CORS gestito da {@link InsightCorsConfig}. Filtri per topic/tipo/membro/
 * correlazione; l'header {@code Last-Event-ID} (gestito dal browser) rinvia gli eventi persi.
 */
@RestController
@RequestMapping("/v1/stream")
public class StreamController {

    private final LiveEventHub hub;

    public StreamController(LiveEventHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestParam(required = false) String topics,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String correlationId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Filter filter = new Filter(csv(topics), csv(types), memberId, correlationId);
        return hub.subscribe(filter, lastEventId);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
