package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.api.SegmentViews.MemberSample;
import io.loyaltyhub.member.api.SegmentViews.PreviewRequest;
import io.loyaltyhub.member.api.SegmentViews.PreviewResult;
import io.loyaltyhub.member.api.SegmentViews.RefreshResult;
import io.loyaltyhub.member.api.SegmentViews.SegmentRequest;
import io.loyaltyhub.member.api.SegmentViews.SegmentView;
import io.loyaltyhub.member.api.SegmentViews.StaticMembersRequest;
import io.loyaltyhub.member.application.SegmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Segmenti (docs/servizi/member-service.md §3; BO-04). Letture per tutti; scritture con la capacità
 * {@code segment.write} = ADMIN, MARKETING (docs/08 §2). {@code {id}} accetta id o codice (docs/06 §2).
 */
@RestController
@RequestMapping("/v1/segments")
public class SegmentsController {

    private final SegmentService service;

    public SegmentsController(SegmentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<SegmentView> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return service.list(q, type, status, page, size);
    }

    @GetMapping("/{id}")
    public SegmentView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @RequiresRole({Role.MARKETING})
    public ResponseEntity<SegmentView> create(@RequestBody SegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @RequiresRole({Role.MARKETING})
    public SegmentView update(@PathVariable String id, @RequestBody SegmentRequest request) {
        return service.update(id, request);
    }

    /** Anteprima senza salvare: {@code {criteria}} → {@code {count, sample[10]}}. Lettura: ammessa a tutti. */
    @PostMapping("/preview")
    public PreviewResult preview(@RequestBody PreviewRequest request) {
        return service.preview(request.criteria());
    }

    @PostMapping("/{id}/refresh")
    @RequiresRole({Role.MARKETING})
    public RefreshResult refresh(@PathVariable String id) {
        return service.refresh(id);
    }

    /**
     * Membri del segmento, per pagina (elenco di BO-04).
     * SPEC-GAP: Q-87 — la scheda servizio §3 elenca solo il PUT su questo path; la lettura serve a BO-04 (membri attuali,
     * elenco da modificare di uno statico).
     */
    @GetMapping("/{id}/members")
    public PageResponse<MemberSample> members(@PathVariable String id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.members(id, page, size);
    }

    /** Solo {@code STATIC}: sostituisce l'elenco. */
    @PutMapping("/{id}/members")
    @RequiresRole({Role.MARKETING})
    public RefreshResult replaceMembers(@PathVariable String id, @RequestBody StaticMembersRequest request) {
        return service.replaceMembers(id, request.memberIds());
    }
}
