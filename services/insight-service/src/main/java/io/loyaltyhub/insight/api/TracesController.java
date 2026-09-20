package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.insight.trace.Trace;
import io.loyaltyhub.insight.trace.Trace.TraceSummary;
import io.loyaltyhub.insight.trace.TraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Tracciati (docs/servizi/insight-service.md §3): l'albero azione → valutazione → effetti → fatti con l'esito.
 * Usato da BO-25 e dall'indicatore "in elaborazione" del portale (via SSE filtrato per correlationId).
 */
@RestController
@RequestMapping("/v1/traces")
public class TracesController {

    private static final int MAX_LIMIT = 100;

    private final TraceService traces;

    public TracesController(TraceService traces) {
        this.traces = traces;
    }

    public record TracesPage(List<TraceSummary> items, int count) {
    }

    @GetMapping
    public TracesPage list(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<TraceSummary> items = traces.recent(memberId, parseInstant(from), parseInstant(to), capped);
        return new TracesPage(items, items.size());
    }

    @GetMapping("/{correlationId}")
    public Trace byCorrelation(@PathVariable String correlationId) {
        return traces.trace(correlationId)
                .orElseThrow(() -> LhException.notFound("Tracciato non trovato: " + correlationId));
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
