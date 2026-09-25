package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
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
 * Elenco paginato ({@code ?page&size}, risposta {@code {items, page}}, docs/06 §2), uno per {@code correlationId}.
 * Usato da BO-25 e dall'indicatore "in elaborazione" del portale (via SSE filtrato per correlationId).
 */
@RestController
@RequestMapping("/v1/traces")
public class TracesController {

    private static final int DEFAULT_SIZE = 50;

    private final TraceService traces;

    public TracesController(TraceService traces) {
        this.traces = traces;
    }

    @GetMapping
    public PageResponse<TraceSummary> list(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        Paging p = Paging.of(page, size, limit, DEFAULT_SIZE);
        Instant fromI = Paging.instant(from);
        Instant toI = Paging.instant(to);
        List<TraceSummary> items = traces.recent(memberId, fromI, toI, p.size(), p.offset());
        return PageResponse.of(items, p.page(), p.size(), traces.count(memberId, fromI, toI));
    }

    /** Tracciato di un {@code correlationId}; 404 se non c'è ancora nessun evento né voce DLQ (Q-N7). */
    @GetMapping("/{correlationId}")
    public Trace byCorrelation(@PathVariable String correlationId) {
        return traces.trace(correlationId)
                .orElseThrow(() -> LhException.notFound("Tracciato non trovato: " + correlationId));
    }
}
