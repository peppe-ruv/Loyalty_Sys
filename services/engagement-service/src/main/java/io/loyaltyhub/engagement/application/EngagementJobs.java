package io.loyaltyhub.engagement.application;

import io.loyaltyhub.engagement.infra.InboxRepository;
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
 * 180 giorni, ogni notte; fine automatica dei contenuti col calendario scaduto, ogni 10 minuti. Attivo solo con {@code loyaltyhub.jobs.enabled=true} (spento in demo, come negli altri servizi).
 */
@Component
@Lazy(false)
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class EngagementJobs {

    private static final Logger log = LoggerFactory.getLogger(EngagementJobs.class);
    static final Duration INBOX_RETENTION = Duration.ofDays(180);

    private final InboxRepository inbox;
    private final ContentService contents;
    private final Clock clock;

    public EngagementJobs(InboxRepository inbox, ContentService contents, Clock clock) {
        this.inbox = inbox;
        this.contents = contents;
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
    }
}
