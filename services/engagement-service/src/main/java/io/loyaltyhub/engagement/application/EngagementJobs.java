package io.loyaltyhub.engagement.application;

import io.loyaltyhub.engagement.infra.InboxRepository;
import io.loyaltyhub.engagement.infra.PopupViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Job schedulati di engagement (docs/servizi/engagement-service.md §5): pulizia dei messaggi dell'inbox più vecchi di
 * 180 giorni, delle viste dei pop-up oltre 90 e delle consegne dei webhook oltre 14, ogni notte; fine automatica dei contenuti col calendario scaduto, ogni 10 minuti. Attivo solo con {@code loyaltyhub.jobs.enabled=true} (spento in demo, come negli altri servizi).
 */
@Component
@Lazy(false)
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class EngagementJobs {

    private static final Logger log = LoggerFactory.getLogger(EngagementJobs.class);
    static final Duration INBOX_RETENTION = Duration.ofDays(180);

    static final int POPUP_VIEW_RETENTION_DAYS = 90;
    static final Duration WEBHOOK_DELIVERY_RETENTION = Duration.ofDays(14);

    private final InboxRepository inbox;
    private final PopupViewRepository popupViews;
    private final ContentService contents;
    private final WebhookService webhooks;
    private final Clock clock;

    public EngagementJobs(InboxRepository inbox, PopupViewRepository popupViews, ContentService contents,
                          WebhookService webhooks, Clock clock) {
        this.inbox = inbox;
        this.popupViews = popupViews;
        this.contents = contents;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void endExpiredContents() {
        int ended = contents.endExpired(clock.instant());
        if (ended > 0) {
            log.info("Fine contenuti: {} passati a ENDED", ended);
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Rome")
    public void purgeInbox() {
        int removed = inbox.deleteOlderThan(clock.instant().minus(INBOX_RETENTION));
        if (removed > 0) {
            log.info("Pulizia inbox: rimossi {} messaggi oltre {} giorni", removed, INBOX_RETENTION.toDays());
        }
        int views = popupViews.deleteOlderThan(java.time.LocalDate.now(clock).minusDays(POPUP_VIEW_RETENTION_DAYS));
        if (views > 0) {
            log.info("Pulizia viste pop-up: rimosse {} oltre {} giorni", views, POPUP_VIEW_RETENTION_DAYS);
        }
        int deliveries = webhooks.purgeDeliveries(clock.instant().minus(WEBHOOK_DELIVERY_RETENTION));
        if (deliveries > 0) {
            log.info("Pulizia consegne webhook: rimosse {} oltre {} giorni", deliveries, WEBHOOK_DELIVERY_RETENTION.toDays());
        }
    }
}
