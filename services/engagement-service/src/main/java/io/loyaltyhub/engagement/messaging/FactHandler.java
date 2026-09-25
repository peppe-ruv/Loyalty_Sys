package io.loyaltyhub.engagement.messaging;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.inbox.EventHandler;
import io.loyaltyhub.engagement.application.NotificationService;
import io.loyaltyhub.engagement.application.WebhookService;
import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.engagement.infra.MemberErasureRepository;
import io.loyaltyhub.engagement.infra.MemberSnapshotRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Tutti i fatti ({@code io.loyaltyhub.fact.*}, docs/05 §8): prima lo snapshot del membro ({@code member.*},
 * {@code tier.*}, {@code member.segment.*}), poi le regole di notifica (F-MSG-01), infine l'abbinamento ai webhook
 * (F-WBH-01: solo righe {@code webhook_delivery} nella stessa transazione; l'HTTP parte dallo scheduler). L'ordine
 * conta: il messaggio di benvenuto di {@code member.registered} trova già il nome per {@code {{member.firstName}}}.
 */
@Component
public class FactHandler implements EventHandler {

    private final MemberSnapshotRepository members;
    private final NotificationService notifications;
    private final WebhookService webhooks;
    private final MemberErasureRepository erasure;

    public FactHandler(MemberSnapshotRepository members, NotificationService notifications, WebhookService webhooks,
                       MemberErasureRepository erasure) {
        this.erasure = erasure;
        this.members = members;
        this.notifications = notifications;
        this.webhooks = webhooks;
    }

    @Override
    public Set<String> handledTypes() {
        return Set.of(LhEventTypes.Fact.PREFIX + "*");
    }

    @Override
    public void handle(LhEvent<JsonNode> event) {
        if (LhEventTypes.Fact.MESSAGE_DELIVERED.equals(event.type())) {
            return; // mai oggetto di regole né di webhook (docs/servizi/engagement-service.md §5)
        }
        updateSnapshot(event);
        notifications.apply(event);
        webhooks.enqueue(event);
    }

    private void updateSnapshot(LhEvent<JsonNode> event) {
        String memberId = event.memberId();
        JsonNode d = event.data();
        if (memberId == null || d == null) {
            return;
        }
        switch (event.type()) {
            case LhEventTypes.Fact.MEMBER_REGISTERED, LhEventTypes.Fact.MEMBER_UPDATED ->
                    members.upsertProfile(memberId, text(d, "firstName"), d.path("status").asString("ACTIVE"),
                            registeredAt(d));
            case LhEventTypes.Fact.MEMBER_STATUS_CHANGED -> {
                if (d.hasNonNull("newStatus")) {
                    members.updateStatus(memberId, d.get("newStatus").asString());
                }
            }
            case LhEventTypes.Fact.TIER_UPGRADED, LhEventTypes.Fact.TIER_DOWNGRADED -> {
                if (d.hasNonNull("newTier")) {
                    members.updateTier(memberId, d.get("newTier").asString());
                }
            }
            case LhEventTypes.Fact.TIER_RETAINED -> {
                if (d.hasNonNull("tier")) {
                    members.updateTier(memberId, d.get("tier").asString());
                }
            }
            case LhEventTypes.Fact.MEMBER_SEGMENT_ENTERED -> {
                if (d.hasNonNull("segmentCode")) {
                    members.addSegment(memberId, d.get("segmentCode").asString());
                }
            }
            case LhEventTypes.Fact.MEMBER_SEGMENT_LEFT -> {
                if (d.hasNonNull("segmentCode")) {
                    members.removeSegment(memberId, d.get("segmentCode").asString());
                }
            }
            default -> {
                // nessun effetto sullo snapshot
            }
        }
        // Anonimizzazione (F-MBR-05, M7.5): dopo l'aggiornamento (che conserva il nome noto, usato per ripulire i testi).
        if (PersonalData.isAnonymization(event)) {
            erasure.erase(memberId);
        }
    }

    private static java.time.Instant registeredAt(JsonNode d) {
        String v = text(d, "registeredAt");
        try {
            return v == null ? null : java.time.Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }
}
