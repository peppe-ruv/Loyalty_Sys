package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.ContentService.PopupView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pop-up del portale (docs/servizi/engagement-service.md §3, §5; docs/09 §1; F-CNT-02): il prossimo da mostrare
 * ({@code 204} se nessuno) e la registrazione della vista alla chiusura. Il membro viene solo dal token (Q-410, ADR-048,
 * docs/06 §3.4): nessun {@code memberId} in query; in {@code demo} lo legge l'interceptor ({@code memberId} o
 * {@code X-LH-Member}) e il campo {@code memberId} del corpo di {@code seen} è deprecato. La vista è sempre del membro
 * del principal: il pop-up è uguale per tutti, non c'è l'oggetto di un altro membro da toccare.
 */
@RestController
@RequestMapping("/v1/portal/popups")
public class PortalPopupsController {

    public record SeenRequest(@Schema(deprecated = true, description = "solo profilo demo") String memberId,
                              Boolean dismissed) {
    }

    private final ContentService service;

    public PortalPopupsController(ContentService service) {
        this.service = service;
    }

    @GetMapping("/next")
    @Transactional(readOnly = true)
    @MemberEndpoint
    public ResponseEntity<PopupView> next(MemberPrincipal principal) {
        return service.nextPopup(principal.idOrNull()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/seen")
    @MemberEndpoint
    public ResponseEntity<Void> seen(@PathVariable String id, MemberPrincipal principal,
                                     @RequestBody(required = false) SeenRequest r) {
        service.seen(id, principal.merge(r == null ? null : r.memberId()), r != null && Boolean.TRUE.equals(r.dismissed()));
        return ResponseEntity.noContent().build();
    }
}
