package it.iren.loyalty.rulesengine.campaign;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.Map;

/**
 * Campagne di automazione (RF-86): girano dopo la mezzanotte (fuso del programma) ogni giorno / giorni della settimana /
 * giorni del mese, oppure per compleanno e anniversario, su un pubblico (tutti, segmenti, tier, filtro). Per ogni membro
 * del pubblico emette l'azione interna SCHEDULE_TICK con riferimento alla campagna: il consumer la valuta come le altre,
 * con gli stessi limiti e budget. Il pubblico è paginato dal read-model (tetto 200.000 membri per campagna, come OL).
 */
@Component
public class AutomationScheduler {
    private final CampaignSource campaigns;
    private final AudienceSource audience;
    private final KafkaTemplate<String, byte[]> kafka;

    public AutomationScheduler(CampaignSource campaigns, AudienceSource audience, KafkaTemplate<String, byte[]> kafka) {
        this.campaigns = campaigns; this.audience = audience; this.kafka = kafka;
    }

    @Scheduled(cron = "${campaigns.automation-cron:0 5 0 * * *}", zone = "Europe/Rome")
    public void tick() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Rome"));
        Instant now = Instant.now();
        for (Campaign c : campaigns.scheduled()) {
            if (!c.isActiveAt(now) || !dueToday(c, today)) continue;
            String day = today.toString();
            audience.membersOf(c).forEach(memberId -> {
                var a = new RewardingAction("SCHEDULE_TICK", "automation:" + c.id() + ":" + memberId + ":" + day, c.id(), now, null, Map.of("campaignId", c.id(), "date", day));
                kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.ACTION_V1, "urn:iren:loyalty:automation", "member:" + memberId, a)));
            });
        }
    }

    /** Pianificazione negli attributi custom: schedule=DAILY | WEEKLY:MON,THU | MONTHLY:1,15 | BIRTHDAY | ANNIVERSARY. */
    static boolean dueToday(Campaign c, LocalDate today) {
        String s = c.customAttributes().getOrDefault("schedule", "DAILY");
        if (s.startsWith("WEEKLY:")) return List.of(s.substring(7).split(",")).contains(today.getDayOfWeek().name().substring(0, 3));
        if (s.startsWith("MONTHLY:")) return List.of(s.substring(8).split(",")).contains(String.valueOf(today.getDayOfMonth()));
        return true; // DAILY, BIRTHDAY e ANNIVERSARY: il pubblico è già filtrato dal read-model sul giorno
    }

    /** Porta verso il read-model: membri del pubblico della campagna (segmenti, tier, compleanno/anniversario oggi). */
    public interface AudienceSource { java.util.stream.Stream<String> membersOf(Campaign c); }
}
