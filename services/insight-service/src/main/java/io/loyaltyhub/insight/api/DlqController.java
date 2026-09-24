package io.loyaltyhub.insight.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.insight.application.DlqService;
import io.loyaltyhub.insight.domain.DlqEntry;
import io.loyaltyhub.insight.infra.DlqRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DLQ (docs/servizi/insight-service.md §3, docs/08 §BO-27; F-INS-05): elenco filtrato per {@code status, consumer,
 * errorCode}, dettaglio, e le due azioni ADMIN {@code reprocess} / {@code discard} (§5).
 */
@RestController
@RequestMapping("/v1/dlq")
public class DlqController {

    private static final int MAX_SIZE = 200;

    private final DlqRepository repo;
    private final DlqService service;

    public DlqController(DlqRepository repo, DlqService service) {
        this.repo = repo;
        this.service = service;
    }

    /** Corpo facoltativo di riprocessa/scarta: la nota (obbligatoria per scartare). */
    public record ResolveRequest(String note) {
    }

    @GetMapping
    public PageResponse<DlqEntry> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String consumer,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        int capped = Math.min(Math.max(size, 1), MAX_SIZE);
        int number = Math.max(page, 0);
        List<DlqEntry> items = repo.search(status, consumer, errorCode, capped, number * capped);
        return PageResponse.of(items, number, capped, repo.count(status, consumer, errorCode));
    }

    @GetMapping("/{id}")
    public DlqEntry get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/reprocess")
    @RequiresRole({Role.ADMIN})
    public DlqEntry reprocess(@PathVariable String id, @RequestBody(required = false) ResolveRequest body) {
        return service.reprocess(id, body == null ? null : body.note());
    }

    @PostMapping("/{id}/discard")
    @RequiresRole({Role.ADMIN})
    public DlqEntry discard(@PathVariable String id, @RequestBody(required = false) ResolveRequest body) {
        return service.discard(id, body == null ? null : body.note());
    }
}
