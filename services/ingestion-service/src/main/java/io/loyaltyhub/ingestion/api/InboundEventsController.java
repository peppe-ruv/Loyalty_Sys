package io.loyaltyhub.ingestion.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.ingestion.infra.InboundEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Monitor ingressi (docs/servizi/ingestion-service.md §3, BO-26): elenco per esito. Retry/match in M7. */
@RestController
@RequestMapping("/v1/inbound-events")
public class InboundEventsController {

    private final InboundEventRepository repository;

    public InboundEventsController(InboundEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<InboundEventRepository.InboundRow> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String memberId,
            @RequestParam(defaultValue = "100") int limit) {
        return repository.search(status, source, type, memberId, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/{id}")
    public InboundEventRepository.InboundRow get(@PathVariable String id) {
        return repository.findById(id).orElseThrow(() -> LhException.notFound("Evento non trovato: " + id));
    }
}
