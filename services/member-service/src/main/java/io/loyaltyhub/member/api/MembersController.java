package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.application.MemberService;
import io.loyaltyhub.member.application.SegmentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Anagrafica dei membri (docs/servizi/member-service.md §3). Ricerca con filtri, creazione, dettaglio,
 * modifica parziale con lock ottimistico, cambio stato. Ogni scrittura emette il fatto corrispondente.
 */
@RestController
@RequestMapping("/v1/members")
public class MembersController {

    private final MemberService service;
    private final SegmentService segments;

    public MembersController(MemberService service, SegmentService segments) {
        this.service = service;
        this.segments = segments;
    }

    @GetMapping
    public PageResponse<MemberView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String segment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(q, status, tier, segment, page, size);
    }

    /** Segmenti di appartenenza (scheda {@code segments} di BO-03, docs/08). */
    @GetMapping("/{id}/segments")
    public List<SegmentViews.MemberSegmentView> segments(@PathVariable String id) {
        return segments.segmentsOf(id);
    }

    @PostMapping
    public ResponseEntity<MemberView> create(@RequestBody CreateMemberRequest request) {
        MemberView created = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public MemberView get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public MemberView update(@PathVariable String id, @RequestBody UpdateMemberRequest request) {
        return service.update(id, request);
    }

    /** Cambio stato (F-MBR-04, BO-03): capacità {@code member.write} di docs/08 §2 → ADMIN e CARE. */
    @PostMapping("/{id}/status")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public MemberView changeStatus(@PathVariable String id, @RequestBody StatusChangeRequest request) {
        return service.changeStatus(id, request);
    }

    /**
     * Anonimizzazione irreversibile (F-MBR-05, BO-03): conferma con l'id digitato. Solo ADMIN (capacità
     * {@code member.anonymize} di docs/08 §2).
     */
    @PostMapping("/{id}/anonymize")
    @RequiresRole({Role.ADMIN})
    public MemberView anonymize(@PathVariable String id, @RequestBody(required = false) AnonymizeRequest request) {
        return service.anonymize(id, request == null ? null : request.confirm());
    }
}
