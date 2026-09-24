package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.engagement.application.InboxService;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.infra.InboxRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro dei messaggi consegnati (docs/servizi/engagement-service.md §3): scheda {@code log} di BO-19 e Scheda 360°.
 * Tutti i canali, dal più recente, paginato {@code {items, page}}.
 */
@RestController
@RequestMapping("/v1/messages")
public class MessagesController {

    private final InboxService inbox;

    public MessagesController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public PageResponse<InboxMessage> list(@RequestParam(required = false) String memberId,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(required = false) String channel,
                                           @RequestParam(required = false) String templateCode,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return inbox.search(new InboxRepository.Filter(memberId, category, channel, templateCode), page, size);
    }
}
