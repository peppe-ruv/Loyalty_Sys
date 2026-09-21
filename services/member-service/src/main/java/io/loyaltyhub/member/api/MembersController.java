package io.loyaltyhub.member.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.member.application.MemberService;
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

/**
 * Anagrafica dei membri (docs/servizi/member-service.md §3). Ricerca con filtri, creazione, dettaglio,
 * modifica parziale con lock ottimistico, cambio stato. Ogni scrittura emette il fatto corrispondente.
 */
@RestController
@RequestMapping("/v1/members")
public class MembersController {

    private final MemberService service;

    public MembersController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<MemberView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tier,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(q, status, tier, page, size);
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

    @PostMapping("/{id}/status")
    public MemberView changeStatus(@PathVariable String id, @RequestBody StatusChangeRequest request) {
        return service.changeStatus(id, request);
    }
}
