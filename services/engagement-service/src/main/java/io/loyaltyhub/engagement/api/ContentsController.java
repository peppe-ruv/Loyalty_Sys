package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.ContentService.ContentRequest;
import io.loyaltyhub.engagement.application.ContentService.Preview;
import io.loyaltyhub.engagement.domain.ContentItem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contenuti del CMS (docs/servizi/engagement-service.md §3; BO-18, F-CNT-01…04). Scritture con la capacità
 * {@code content.write} (ADMIN, MARKETING; docs/08 §2); i contenuti non richiedono approvazione.
 */
@RestController
@RequestMapping("/v1/contents")
public class ContentsController {

    public record TransitionRequest(String action) {
    }

    private final ContentService service;

    public ContentsController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ContentItem> list(@RequestParam(required = false) String kind,
                                  @RequestParam(required = false) String placement,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String q) {
        return service.list(kind, placement, status, q);
    }

    /** Cosa vedrebbe quel membro adesso nel posizionamento, col motivo di esclusione degli altri contenuti. */
    @GetMapping("/preview")
    @Transactional(readOnly = true)
    public Preview preview(@RequestParam(required = false) String memberId, @RequestParam(required = false) String placement) {
        return service.preview(memberId, placement);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ContentItem get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<ContentItem> create(@RequestBody ContentRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @PutMapping("/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ContentItem update(@PathVariable String id, @RequestBody ContentRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/transitions")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ContentItem transition(@PathVariable String id, @RequestBody TransitionRequest r) {
        return service.transition(id, r == null ? null : r.action());
    }

    @PostMapping("/{id}/duplicate")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<ContentItem> duplicate(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(id));
    }
}
