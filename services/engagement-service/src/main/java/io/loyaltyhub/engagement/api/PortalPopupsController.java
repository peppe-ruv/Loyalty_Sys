package io.loyaltyhub.engagement.api;

import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.ContentService.PopupView;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pop-up del portale (docs/servizi/engagement-service.md §3, §5; docs/09 §1; F-CNT-02): il prossimo da mostrare
 * ({@code 204} se nessuno) e la registrazione della vista alla chiusura.
 */
@RestController
@RequestMapping("/v1/portal/popups")
public class PortalPopupsController {

    public record SeenRequest(String memberId, Boolean dismissed) {
    }

    private final ContentService service;

    public PortalPopupsController(ContentService service) {
        this.service = service;
    }

    @GetMapping("/next")
    @Transactional(readOnly = true)
    public ResponseEntity<PopupView> next(@RequestParam(required = false) String memberId) {
        return service.nextPopup(memberId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/seen")
    public ResponseEntity<Void> seen(@PathVariable String id, @RequestBody(required = false) SeenRequest r) {
        service.seen(id, r == null ? null : r.memberId(), r != null && Boolean.TRUE.equals(r.dismissed()));
        return ResponseEntity.noContent().build();
    }
}
