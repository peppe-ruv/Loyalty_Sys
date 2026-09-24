package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.engagement.application.InboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbox del portale (docs/servizi/engagement-service.md §3; PT-12 e campanella, F-MSG-01). Solo canale {@code INAPP}.
 * {@code memberId} esplicito (docs/06 §2): in query o, per le scritture, anche nel corpo {@code {memberId}}.
 */
@RestController
@RequestMapping("/v1/portal/inbox")
public class PortalInboxController {

    public record MemberRequest(String memberId) {
    }

    private final InboxService inbox;

    public PortalInboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public PageResponse<InboxService.PortalMessage> inbox(@RequestParam(required = false) String memberId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return inbox.portalInbox(memberId, page, size);
    }

    @GetMapping("/unread-count")
    public InboxService.UnreadCount unreadCount(@RequestParam(required = false) String memberId) {
        return inbox.unread(memberId);
    }

    @PostMapping("/{id}/read")
    public InboxService.PortalMessage read(@PathVariable String id, @RequestParam(required = false) String memberId,
                                           @RequestBody(required = false) MemberRequest body) {
        return inbox.markRead(id, member(memberId, body));
    }

    @PostMapping("/read-all")
    public InboxService.ReadAllOutcome readAll(@RequestParam(required = false) String memberId,
                                               @RequestBody(required = false) MemberRequest body) {
        return inbox.markAllRead(member(memberId, body));
    }

    private static String member(String param, MemberRequest body) {
        return param != null && !param.isBlank() ? param : body == null ? null : body.memberId();
    }
}
