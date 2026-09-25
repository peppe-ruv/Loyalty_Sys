package io.loyaltyhub.insight.api;

import io.loyaltyhub.insight.infra.DlqRepository;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository.TopicStat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stato della pipeline ({@code GET /v1/pipeline/status}, docs/servizi/insight-service.md §3, F-INS-06, BO-24):
 * per topic ultimo evento, volumi 1 h / 24 h e ritardo stimato; per servizio l'ultimo fatto prodotto.
 */
@RestController
@RequestMapping("/v1/pipeline")
public class PipelineController {

    private static final String DLQ_TOPIC = "lh.dlq.v1";

    private final TopicStatRepository topicStats;
    private final EventStoreRepository events;
    private final DlqRepository dlq;

    public PipelineController(TopicStatRepository topicStats, EventStoreRepository events, DlqRepository dlq) {
        this.topicStats = topicStats;
        this.events = events;
        this.dlq = dlq;
    }

    /**
     * Un topic: ultimo evento ({@code time} più recente), conteggio totale, offset per partizione, volumi sull'istante
     * d'arrivo nell'ultima ora e nelle ultime 24 h, ritardo stimato ({@code lagMs}: arrivo in insight − timestamp del
     * record Kafka, sull'ultimo record) e istante dell'ultimo arrivo.
     */
    public record TopicStatus(String topic, Instant lastEventAt, long countTotal, String lastOffsetByPartition,
                              long count1h, long count24h, Long lagMs, Instant lastReceivedAt) {
    }

    /** Un servizio: ultimo fatto prodotto (tipo breve, id, arrivo) e fatti visti dall'ultimo reset. */
    public record ServiceStatus(String service, Instant lastFactAt, String lastFactType, String lastEventId,
                                long factsTotal) {
    }

    public record PipelineStatus(List<TopicStatus> topics, List<ServiceStatus> services) {
    }

    @GetMapping("/status")
    public PipelineStatus status() {
        Duration hour = Duration.ofHours(1);
        Duration day = Duration.ofHours(24);
        Map<String, Long> last1h = events.countByTopicWithin(hour);
        Map<String, Long> last24h = events.countByTopicWithin(day);
        List<TopicStatus> topics = topicStats.findAll().stream().map(t -> {
            boolean isDlq = DLQ_TOPIC.equals(t.topic());
            long c1h = isDlq ? dlq.countWithin(hour) : last1h.getOrDefault(t.topic(), 0L);
            long c24h = isDlq ? dlq.countWithin(day) : last24h.getOrDefault(t.topic(), 0L);
            return status(t, c1h, c24h);
        }).toList();
        List<ServiceStatus> services = topicStats.findServices().stream()
                .map(s -> new ServiceStatus(s.service(), s.lastFactAt(), s.lastFactType(), s.lastEventId(),
                        s.factsTotal()))
                .toList();
        return new PipelineStatus(topics, services);
    }

    private static TopicStatus status(TopicStat t, long c1h, long c24h) {
        return new TopicStatus(t.topic(), t.lastEventAt(), t.countTotal(), t.lastOffsetByPartition(), c1h, c24h,
                t.lastLagMs(), t.lastReceivedAt());
    }
}
