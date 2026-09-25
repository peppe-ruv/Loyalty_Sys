package io.loyaltyhub.campaign.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Job schedulati di campaign (docs/servizi/campaign-service.md §5): fine automatica delle campagne {@code LIVE}/
 * {@code PAUSED} con {@code schedule.endAt} superato, ogni minuto. Attivo solo con {@code loyaltyhub.jobs.enabled=true}
 * (spento in demo, come negli altri servizi).
 */
@Component
@Lazy(false)
@ConditionalOnProperty(name = "loyaltyhub.jobs.enabled", havingValue = "true")
public class CampaignJobs {

    private static final Logger log = LoggerFactory.getLogger(CampaignJobs.class);

    private final CampaignAdminService campaigns;
    private final Clock clock;

    public CampaignJobs(CampaignAdminService campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void endExpiredCampaigns() {
        int ended = campaigns.endExpired(clock.instant());
        if (ended > 0) {
            log.info("Fine campagne: {} passate a ENDED", ended);
        }
    }
}
