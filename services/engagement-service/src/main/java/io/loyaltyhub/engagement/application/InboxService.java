package io.loyaltyhub.engagement.application;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.engagement.domain.InboxMessage;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.domain.TemplateEngine;
import io.loyaltyhub.engagement.infra.InboxRepository;
import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consegna nell'inbox e letture (docs/03 §9, docs/servizi/engagement-service.md §3–§5; F-MSG-01). Ogni messaggio
 * nuovo produce il fatto {@code message.delivered} (EVT-FACT-60) come figlio dell'evento sorgente, nella stessa
 * transazione; un duplicato (stessa terna membro, evento sorgente, template) non produce nulla.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** Voce del portale (PT-12). */
    public record PortalMessage(String id, String category, String title, String body, String icon, String linkTarget,
                                Instant createdAt, boolean read, Instant readAt) {
        static PortalMessage of(InboxMessage m) {
            return new PortalMessage(m.id(), m.category(), m.title(), m.body(), m.icon(), m.linkTarget(), m.createdAt(),
                    m.readAt() != null, m.readAt());
        }
    }

    public record UnreadCount(String memberId, long unread) {
    }

    public record ReadAllOutcome(String memberId, int marked, long unread) {
    }

    private final InboxRepository inbox;
    private final MemberSnapshotRepository members;
    private final MessageContexts contexts;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public InboxService(InboxRepository inbox, MemberSnapshotRepository members, MessageContexts contexts,
                        LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.inbox = inbox;
        this.members = members;
        this.contexts = contexts;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Rende il template sul contesto dell'evento e lo consegna al membro. Da chiamare dentro la transazione del
     * consumo idempotente. I membri anonimizzati e inattivi non ricevono messaggi (Q-70, Q-180).
     *
     * @param data          spazio {@code data.*} del contesto
     * @param sourceEventId chiave di deduplica dell'evento sorgente
     * @return il messaggio consegnato ora; vuoto se duplicato o membro anonimizzato o inattivo
     */
    public Optional<InboxMessage> deliver(String memberId, MessageTemplate template, JsonNode data, LhEvent<?> source,
                                          String sourceEventId) {
        // SPEC-GAP: Q-70 — scelta conservativa: un membro anonimizzato non riceve messaggi (i BLOCKED sì).
        // Q-180 DECISA: nemmeno un membro INACTIVE; senza snapshot il messaggio parte (lo snapshot può arrivare dopo).
        Optional<String> status = members.find(memberId).map(s -> s.status());
        if (status.filter(s -> "ANONYMIZED".equals(s) || "INACTIVE".equals(s)).isPresent()) {
            log.info("Membro {} {}: nessun messaggio {}", memberId, status.get(), template.code());
            return Optional.empty();
        }
        String shortType = MessageContexts.shortType(source.type());
        String correlation = source.lhcorrelationid() != null ? source.lhcorrelationid() : source.id();
        JsonNode ctx = contexts.build(memberId, data, new MessageContexts.EventMeta(source.id(), shortType, source.time(),
                source.subject(), source.source(), correlation));
        InboxMessage m = new InboxMessage(Ulid.next(clock), memberId, template.code(), template.channel(),
                TemplateEngine.renderText(template.titleTpl(), ctx), TemplateEngine.renderText(template.bodyTpl(), ctx),
                template.icon(), template.linkTarget(), template.category(), sourceEventId, shortType, correlation,
                clock.instant(), null);
        if (!inbox.insertIfAbsent(m)) {
            log.debug("Messaggio {} già consegnato a {} per l'evento {}", template.code(), memberId, sourceEventId);
            return Optional.empty();
        }
        Map<String, Object> delivered = new LinkedHashMap<>();
        delivered.put("templateCode", m.templateCode());
        delivered.put("channel", m.channel());
        delivered.put("inboxMessageId", m.id());
        delivered.put("category", m.category());
        outbox.write(events.childOf(source, LhEventTypes.Fact.MESSAGE_DELIVERED, delivered));
        return Optional.of(m);
    }

    // ---------- letture ----------

    @Transactional(readOnly = true)
    public PageResponse<InboxMessage> search(InboxRepository.Filter filter, int page, int size) {
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int s = paging.size();
        int p = paging.page();
        return PageResponse.of(inbox.search(filter, p, s), p, s, inbox.count(filter));
    }

    /** Inbox del portale: solo canale {@code INAPP}, dal più recente. */
    // SPEC-GAP: Q-67 — EMAIL_FAKE "produce solo un'anteprima consultabile da BO-19": resta nel registro /v1/messages ma
    // non entra nell'inbox del portale né nel contatore dei non letti.
    @Transactional(readOnly = true)
    public PageResponse<PortalMessage> portalInbox(String memberId, int page, int size) {
        String member = requireMember(memberId);
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int s = paging.size();
        int p = paging.page();
        InboxRepository.Filter f = new InboxRepository.Filter(member, null, "INAPP", null);
        List<PortalMessage> items = inbox.search(f, p, s).stream().map(PortalMessage::of).toList();
        return PageResponse.of(items, p, s, inbox.count(f));
    }

    @Transactional(readOnly = true)
    public UnreadCount unread(String memberId) {
        String member = requireMember(memberId);
        return new UnreadCount(member, inbox.unreadInApp(member));
    }

    @Transactional
    public PortalMessage markRead(String id, String memberId) {
        String member = requireMember(memberId);
        if (!inbox.markRead(id, member, clock.instant())) {
            throw LhException.notFound("Messaggio non trovato: " + id);
        }
        return inbox.find(id).map(PortalMessage::of).orElseThrow(() -> LhException.notFound("Messaggio non trovato: " + id));
    }

    @Transactional
    public ReadAllOutcome markAllRead(String memberId) {
        String member = requireMember(memberId);
        int marked = inbox.markAllRead(member, clock.instant());
        return new ReadAllOutcome(member, marked, inbox.unreadInApp(member));
    }

    private static String requireMember(String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.badRequest("Parametro memberId obbligatorio.");
        }
        return memberId.trim();
    }
}
