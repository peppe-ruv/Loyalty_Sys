package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.engagement.application.InboxService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbox del portale (docs/servizi/engagement-service.md §3; PT-12 e campanella, F-MSG-01). Solo canale {@code INAPP}.
 * Il membro viene solo dal token (Q-410, ADR-048, docs/06 §3.4): nessun {@code memberId} in query. In {@code demo} lo
 * legge l'interceptor ({@code memberId} o {@code X-LH-Member}); il campo {@code memberId} del corpo delle scritture è
 * deprecato, valido solo in demo. Un messaggio di un altro membro dà {@code 404}.
 */
@RestController
@RequestMapping("/v1/portal/inbox")
public class PortalInboxController {

    /** Corpo (facoltativo) delle scritture: {@code memberId} è deprecato e vale solo nel profilo demo (Q-553). */
    public record MemberRequest(@Schema(deprecated = true, description = "solo profilo demo") String memberId) {
    }

    private final InboxService inbox;

    public PortalInboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    @MemberEndpoint
    public PageResponse<InboxService.PortalMessage> inbox(MemberPrincipal principal,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return inbox.portalInbox(principal.idOrNull(), page, size);
    }

    @GetMapping("/unread-count")
    @MemberEndpoint
    public InboxService.UnreadCount unreadCount(MemberPrincipal principal) {
        return inbox.unread(principal.idOrNull());
    }

    @PostMapping("/{id}/read")
    @MemberEndpoint
    public InboxService.PortalMessage read(@PathVariable String id, MemberPrincipal principal,
                                           @RequestBody(required = false) MemberRequest body) {
        return inbox.markRead(id, principal.merge(body == null ? null : body.memberId()));
    }

    @PostMapping("/read-all")
    @MemberEndpoint
    public InboxService.ReadAllOutcome readAll(MemberPrincipal principal, @RequestBody(required = false) MemberRequest body) {
        return inbox.markAllRead(principal.merge(body == null ? null : body.memberId()));
    }
}
