package io.loyaltyhub.campaign.application;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.infra.CampaignRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cache in memoria delle campagne (docs/servizi/campaign-service.md §5): le {@code LIVE} sono tenute pronte
 * per la valutazione, invalidate a ogni scrittura/transizione e comunque ricaricate ogni 30 s.
 */
@Component
@org.springframework.context.annotation.Lazy(false) // docs/06 §5: attivo anche con lazy-initialization (profilo free)
public class CampaignCache {

    private final CampaignRepository repository;
    private final AtomicReference<List<Campaign>> all = new AtomicReference<>(List.of());

    public CampaignCache(CampaignRepository repository) {
        this.repository = repository;
        reload();
    }

    @Scheduled(fixedDelay = 30_000)
    public final void reload() {
        all.set(repository.findAll());
    }

    /** Campagne {@code LIVE} per la valutazione. */
    public List<Campaign> live() {
        return all.get().stream().filter(c -> c.status() == CampaignStatus.LIVE).toList();
    }

    public List<Campaign> all() {
        return all.get();
    }
}
